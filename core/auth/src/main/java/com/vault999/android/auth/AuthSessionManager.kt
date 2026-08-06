package com.vault999.android.auth

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates restoration and token rotation. Concurrent callers share one in-flight refresh. */
class AuthSessionManager(
    private val store: SessionStore,
    private val transport: AccountSessionTransport,
    private val nowEpochMs: () -> Long,
) {
    private val mutex = Mutex()
    @Volatile private var visibleProjection: AccountProjection = AccountProjection.SignedOut
    private var session: AccountSession? = null
    private var refreshInFlight: CompletableDeferred<AccountProjection>? = null
    // Set before attempting durable deletion. This manager can never re-read stale credentials
    // after logout even when local storage is temporarily unwritable.
    private var localSignOutTombstone = false

    fun projection(): AccountProjection = visibleProjection

    suspend fun restoreAtStartup(): AccountProjection {
        val shouldRefresh = mutex.withLock {
            if (localSignOutTombstone) return@withLock false
            // Keep restoration idempotent while a refresh is in flight.
            session?.let { return@withLock it.requiresRefresh(nowEpochMs()) }
            val restored = try {
                store.read()
            } catch (_: IOException) {
                // An I/O outage is not proof that credentials are invalid or corrupt.
                return@withLock false
            }
            when (restored) {
                SessionReadResult.Missing -> {
                    setSignedOut()
                    false
                }
                SessionReadResult.Corrupt -> {
                    clearLocalSession()
                    false
                }
                is SessionReadResult.Loaded -> {
                    session = restored.session
                    visibleProjection = restored.session.onlineProjection()
                    restored.session.requiresRefresh(nowEpochMs())
                }
            }
        }
        return if (shouldRefresh) refreshExpiredSession() else visibleProjection
    }

    suspend fun completeSignIn(exchange: CallbackConsumption.Accepted): AccountTransportResult<AccountProjection> {
        val material = exchange.claimForExchange() ?: return AccountTransportResult.AuthenticationRejected
        val result = transport.exchange(
            material.ticket,
            material.state,
            material.verifier,
            material.redirectUri,
        )
        return mutex.withLock {
            when (result) {
                is AccountTransportResult.Success -> {
                    persistAndPublish(result.value)
                    AccountTransportResult.Success(visibleProjection)
                }
                is AccountTransportResult.TransientFailure -> result
                AccountTransportResult.AuthenticationRejected -> AccountTransportResult.AuthenticationRejected
            }
        }
    }

    /** Returns a valid access credential or refreshes once for all concurrent callers. */
    suspend fun accessSession(): SessionAccess {
        val beforeRefresh = mutex.withLock {
            val current = session ?: return@withLock SessionAccess.SignedOut
            if (!current.requiresRefresh(nowEpochMs())) SessionAccess.Granted(current) else null
        }
        if (beforeRefresh != null) return beforeRefresh
        refreshExpiredSession()
        return mutex.withLock {
            val current = session ?: return@withLock SessionAccess.SignedOut
            if (!current.requiresRefresh(nowEpochMs())) {
                SessionAccess.Granted(current)
            } else {
                SessionAccess.TemporarilyUnavailable(current.account.copy(cached = true), OFFLINE_CODE)
            }
        }
    }

    /** Performs at most one serialized refresh after an authenticated request returns 401. */
    suspend fun refreshAfterUnauthorized(rejectedSession: AccountSession): SessionAccess {
        refreshExpiredSession(force = true, expected = rejectedSession)
        return mutex.withLock {
            val current = session ?: return@withLock SessionAccess.SignedOut
            if (!current.requiresRefresh(nowEpochMs())) {
                SessionAccess.Granted(current)
            } else {
                SessionAccess.TemporarilyUnavailable(current.account.copy(cached = true), OFFLINE_CODE)
            }
        }
    }

    /** Clears local authentication first; revocation failure never resurrects it. */
    suspend fun logout() {
        val refresh = mutex.withLock {
            val existing = session
            clearLocalSession()
            existing?.let { current -> current.useAccessToken { it } }
        }
        if (refresh != null) transport.revoke(refresh)
    }

    private suspend fun refreshExpiredSession(
        force: Boolean = false,
        expected: AccountSession? = null,
    ): AccountProjection {
        val decision = mutex.withLock {
            val existing = session ?: return@withLock RefreshDecision.Current(AccountProjection.SignedOut)
            if (expected != null && existing !== expected) return@withLock RefreshDecision.Current(existing.onlineProjection())
            if (!force && !existing.requiresRefresh(nowEpochMs())) return@withLock RefreshDecision.Current(existing.onlineProjection())
            refreshInFlight?.let { return@withLock RefreshDecision.Await(it) }
            val deferred = CompletableDeferred<AccountProjection>()
            refreshInFlight = deferred
            RefreshDecision.Run(existing, deferred)
        }
        return when (decision) {
            is RefreshDecision.Current -> decision.projection
            is RefreshDecision.Await -> decision.deferred.await()
            is RefreshDecision.Run -> runRefresh(decision)
        }
    }

    private suspend fun runRefresh(decision: RefreshDecision.Run): AccountProjection {
        val transportResult = try {
            decision.session.useRefreshTokenSuspending { transport.refresh(it) }
        } catch (cancelled: CancellationException) {
            finishCancelledRefresh(decision, cancelled)
            throw cancelled
        } catch (_: Exception) {
            AccountTransportResult.TransientFailure("transport_exception")
        }

        val resolved = try {
            mutex.withLock {
                if (session !== decision.session) visibleProjection else applyRefreshResult(decision.session, transportResult)
            }
        } catch (_: IOException) {
            // A failed atomic rotation retains the previous envelope and identity.
            mutex.withLock { markOfflineIfCurrent(decision.session) }
        }
        mutex.withLock {
            if (refreshInFlight === decision.deferred) refreshInFlight = null
        }
        decision.deferred.complete(resolved)
        return resolved
    }

    private suspend fun finishCancelledRefresh(decision: RefreshDecision.Run, failure: CancellationException) {
        mutex.withLock {
            if (refreshInFlight === decision.deferred) refreshInFlight = null
        }
        decision.deferred.completeExceptionally(failure)
    }

    private fun applyRefreshResult(
        previous: AccountSession,
        result: AccountTransportResult<AccountSession>,
    ): AccountProjection = when (result) {
        is AccountTransportResult.Success -> {
            persistAndPublish(result.value)
            visibleProjection
        }
        is AccountTransportResult.TransientFailure -> markOfflineIfCurrent(previous)
        AccountTransportResult.AuthenticationRejected -> {
            clearLocalSession()
            visibleProjection
        }
    }

    private fun markOfflineIfCurrent(expected: AccountSession): AccountProjection {
        if (session === expected) {
            visibleProjection = AccountProjection.SignedIn(
                expected.account.copy(cached = true),
                AccountAvailability.OFFLINE_CACHED,
            )
        }
        return visibleProjection
    }

    private fun persistAndPublish(newSession: AccountSession) {
        store.write(newSession, nowEpochMs())
        session = newSession
        localSignOutTombstone = false
        visibleProjection = newSession.onlineProjection()
    }

    private fun clearLocalSession() {
        session = null
        visibleProjection = AccountProjection.SignedOut
        localSignOutTombstone = true
        try {
            store.clear()
        } catch (_: IOException) {
            // Memory remains authoritatively signed out. AtomicEncryptedSessionStore writes a
            // durable tombstone before removing the old envelope, so a partial clear is fail-safe.
        }
    }

    private fun setSignedOut(): AccountProjection {
        session = null
        visibleProjection = AccountProjection.SignedOut
        return visibleProjection
    }

    private fun AccountSession.requiresRefresh(now: Long): Boolean =
        accessExpiresAtEpochMs - now <= REFRESH_SAFETY_WINDOW_MS
    private fun AccountSession.onlineProjection(): AccountProjection =
        AccountProjection.SignedIn(account.copy(cached = false), AccountAvailability.ONLINE)

    private sealed interface RefreshDecision {
        data class Current(val projection: AccountProjection) : RefreshDecision
        data class Await(val deferred: CompletableDeferred<AccountProjection>) : RefreshDecision
        data class Run(val session: AccountSession, val deferred: CompletableDeferred<AccountProjection>) : RefreshDecision
    }

    private companion object {
        const val OFFLINE_CODE = "refresh_temporarily_unavailable"
        const val REFRESH_SAFETY_WINDOW_MS = 60_000L
    }
}
