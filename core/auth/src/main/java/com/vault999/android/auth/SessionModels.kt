package com.vault999.android.auth

import com.vault999.android.model.Account

/** A credential that is deliberately useless to string interpolation and logging. */
class OpaqueSecret private constructor(private val value: String) {
    init {
        require(value.isNotBlank()) { "Credential must not be blank" }
        require(value.length <= MAX_CREDENTIAL_CHARS) { "Credential exceeds safety bound" }
    }

    fun <T> use(block: (String) -> T): T = block(value)

    override fun toString(): String = "[REDACTED]"

    companion object {
        private const val MAX_CREDENTIAL_CHARS = 64 * 1024
        fun from(value: String): OpaqueSecret = OpaqueSecret(value)
    }
}

/** In-memory credentials. Deliberately not a data class to avoid generated token accessors/copy/toString. */
class AccountSession private constructor(
    val account: Account,
    private val accessToken: OpaqueSecret,
    private val refreshToken: OpaqueSecret,
    val accessExpiresAtEpochMs: Long,
) {
    init {
        require(account.id.isNotBlank()) { "Account id must not be blank" }
        require(accessExpiresAtEpochMs >= 0) { "Invalid access expiry" }
    }

    fun <T> useAccessToken(block: (OpaqueSecret) -> T): T = block(accessToken)
    suspend fun <T> useAccessTokenSuspending(block: suspend (OpaqueSecret) -> T): T = block(accessToken)
    fun <T> useRefreshToken(block: (OpaqueSecret) -> T): T = block(refreshToken)
    suspend fun <T> useRefreshTokenSuspending(block: suspend (OpaqueSecret) -> T): T = block(refreshToken)

    override fun toString(): String =
        "AccountSession(account=$account, accessToken=[REDACTED], refreshToken=[REDACTED], accessExpiresAtEpochMs=$accessExpiresAtEpochMs)"

    companion object {
        fun create(account: Account, accessToken: String, refreshToken: String, accessExpiresAtEpochMs: Long): AccountSession =
            AccountSession(account, OpaqueSecret.from(accessToken), OpaqueSecret.from(refreshToken), accessExpiresAtEpochMs)

        fun create(
            account: Account,
            accessToken: OpaqueSecret,
            refreshToken: OpaqueSecret,
            accessExpiresAtEpochMs: Long,
        ): AccountSession = AccountSession(account, accessToken, refreshToken, accessExpiresAtEpochMs)
    }
}

enum class AccountAvailability { ONLINE, OFFLINE_CACHED }

sealed interface AccountProjection {
    data object SignedOut : AccountProjection
    data class SignedIn(val account: Account, val availability: AccountAvailability) : AccountProjection
}

sealed interface AccountTransportResult<out T> {
    data class Success<T>(val value: T) : AccountTransportResult<T>
    /** The code must be sanitized and must never contain a response body or credential. */
    data class TransientFailure(val code: String) : AccountTransportResult<Nothing> {
        init {
            require(code.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Unsafe transport error code" }
        }
    }
    data object AuthenticationRejected : AccountTransportResult<Nothing>
}

fun interface TicketExchangeTransport {
    suspend fun exchange(
        ticket: OpaqueSecret,
        state: OpaqueSecret,
        verifier: OpaqueSecret,
        redirectUri: String,
    ): AccountTransportResult<AccountSession>
}

fun interface TokenRefreshTransport {
    suspend fun refresh(refreshToken: OpaqueSecret): AccountTransportResult<AccountSession>
}

fun interface SessionRevocationTransport {
    suspend fun revoke(accessToken: OpaqueSecret): AccountTransportResult<Unit>
}

fun interface AccountProfileTransport {
    suspend fun account(accessToken: OpaqueSecret): AccountTransportResult<Account>
}

interface AccountSessionTransport :
    TicketExchangeTransport,
    TokenRefreshTransport,
    SessionRevocationTransport,
    AccountProfileTransport

sealed interface SessionAccess {
    data class Granted(val session: AccountSession) : SessionAccess {
        override fun toString(): String = "Granted(session=$session)"
    }
    data object SignedOut : SessionAccess
    data class TemporarilyUnavailable(val account: Account?, val code: String) : SessionAccess
}
