package com.vault999.android.auth

import java.net.URI
import java.net.URLDecoder
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

class PendingAuthorization(
    val state: String,
    val verifier: OpaqueSecret,
    val challenge: String,
    val createdAtEpochMs: Long,
) {
    override fun toString(): String =
        "PendingAuthorization(state=[REDACTED], verifier=[REDACTED], challenge=$challenge, createdAtEpochMs=$createdAtEpochMs)"
}

class AuthorizationCallback internal constructor(
    val ticket: OpaqueSecret,
    internal val state: OpaqueSecret,
) {
    override fun toString(): String = "AuthorizationCallback(ticket=[REDACTED], state=[REDACTED])"
}

object Pkce {
    private val random = SecureRandom()

    fun create(nowEpochMs: Long): PendingAuthorization {
        require(nowEpochMs >= 0) { "Invalid authorization timestamp" }
        val state = token(32)
        val verifierText = token(64)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifierText.toByteArray(StandardCharsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return PendingAuthorization(state, OpaqueSecret.from(verifierText), challenge, nowEpochMs)
    }

    private fun token(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
}

object AuthCallbackParser {
    const val REDIRECT_URI = "vault999://auth/callback"
    const val MAX_AGE_MS = 10 * 60 * 1000L
    private const val MAX_CALLBACK_CHARS = 8 * 1024

    fun consume(uriText: String, pending: PendingAuthorization, nowEpochMs: Long): AuthorizationCallback {
        require(uriText.length <= MAX_CALLBACK_CHARS) { "Callback exceeds safety bound" }
        require(isFresh(pending.createdAtEpochMs, nowEpochMs)) { "Authorization attempt expired" }
        val uri = URI(uriText)
        require(
            uri.scheme == "vault999" &&
                uri.rawAuthority == "auth" &&
                uri.rawPath == "/callback" &&
                uri.userInfo == null &&
                uri.port == -1,
        ) { "Unexpected callback target" }
        require(uri.fragment == null) { "Callback fragment rejected" }
        val fields = linkedMapOf<String, String>()
        require(!uri.rawQuery.isNullOrBlank()) { "Callback query missing" }
        uri.rawQuery.split('&').forEach { pair ->
            val parts = pair.split('=', limit = 2)
            require(parts.size == 2) { "Malformed callback field" }
            @Suppress("DEPRECATION")
            val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
            @Suppress("DEPRECATION")
            val value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
            require(key in setOf("ticket", "state") && key !in fields && value.isNotBlank()) { "Unexpected or duplicate callback field" }
            fields[key] = value
        }
        require(fields.size == 2 && fields["state"] == pending.state) { "Authorization state mismatch" }
        return AuthorizationCallback(
            OpaqueSecret.from(fields.getValue("ticket")),
            OpaqueSecret.from(fields.getValue("state")),
        )
    }

    internal fun isFresh(createdAtEpochMs: Long, nowEpochMs: Long): Boolean =
        createdAtEpochMs >= 0 && nowEpochMs >= createdAtEpochMs && nowEpochMs - createdAtEpochMs <= MAX_AGE_MS
}

sealed interface CallbackConsumption {
    /** One-shot exchange capability. Even the same instance cannot be submitted twice concurrently. */
    class Accepted internal constructor(
        private val callback: AuthorizationCallback,
        private val verifier: OpaqueSecret,
    ) : CallbackConsumption {
        private val claimed = AtomicBoolean(false)

        internal fun claimForExchange(): ExchangeMaterial? =
            if (claimed.compareAndSet(false, true)) {
                ExchangeMaterial(callback.ticket, callback.state, verifier, AuthCallbackParser.REDIRECT_URI)
            } else {
                null
            }

        override fun toString(): String = "Accepted(callback=$callback, verifier=[REDACTED])"
    }

    enum class Rejected : CallbackConsumption { NO_PENDING_ATTEMPT, EXPIRED, INVALID_CALLBACK, STORAGE_FAILURE }
}

internal class ExchangeMaterial(
    val ticket: OpaqueSecret,
    val state: OpaqueSecret,
    val verifier: OpaqueSecret,
    val redirectUri: String,
)

/**
 * Persistence boundary for PKCE state. Production integrations must encrypt the verifier at rest and
 * atomically replace/clear it. Implementations must never log or expose serialized contents.
 */
interface PendingAuthorizationStore {
    @Throws(IOException::class)
    fun read(): PendingAuthorization?

    @Throws(IOException::class)
    fun write(pending: PendingAuthorization)

    @Throws(IOException::class)
    fun clear()
}

private class InMemoryPendingAuthorizationStore : PendingAuthorizationStore {
    private var pending: PendingAuthorization? = null
    @Synchronized override fun read(): PendingAuthorization? = pending
    @Synchronized override fun write(pending: PendingAuthorization) { this.pending = pending }
    @Synchronized override fun clear() { pending = null }
}

/** Owns one persisted browser authorization and consumes an exact callback at most once. */
class AuthorizationStateMachine(
    private val store: PendingAuthorizationStore = InMemoryPendingAuthorizationStore(),
    private val createPending: (Long) -> PendingAuthorization = Pkce::create,
) {
    private var pending: PendingAuthorization? = null

    @Synchronized
    @Throws(IOException::class)
    fun begin(nowEpochMs: Long): PendingAuthorization = createPending(nowEpochMs).also {
        store.write(it)
        pending = it
    }

    @Synchronized
    @Throws(IOException::class)
    fun cancel() {
        store.clear()
        pending = null
    }

    @Synchronized
    fun consume(uriText: String, nowEpochMs: Long): CallbackConsumption {
        val attempt = pending ?: try {
            store.read()?.also { pending = it }
        } catch (_: IOException) {
            return CallbackConsumption.Rejected.STORAGE_FAILURE
        } ?: return CallbackConsumption.Rejected.NO_PENDING_ATTEMPT
        if (!AuthCallbackParser.isFresh(attempt.createdAtEpochMs, nowEpochMs)) {
            try {
                store.clear()
                pending = null
            } catch (_: IOException) {
                return CallbackConsumption.Rejected.STORAGE_FAILURE
            }
            return CallbackConsumption.Rejected.EXPIRED
        }
        val callback = try {
            AuthCallbackParser.consume(uriText, attempt, nowEpochMs)
        } catch (_: IllegalArgumentException) {
            return CallbackConsumption.Rejected.INVALID_CALLBACK
        }
        // Durable clear happens before issuing the one-shot exchange capability. After a process
        // restart, the callback therefore cannot recover the verifier for a second exchange.
        try {
            store.clear()
            pending = null
        } catch (_: IOException) {
            return CallbackConsumption.Rejected.STORAGE_FAILURE
        }
        return CallbackConsumption.Accepted(callback, attempt.verifier)
    }

    @Synchronized
    fun hasPendingAttempt(): Boolean = pending != null
}
