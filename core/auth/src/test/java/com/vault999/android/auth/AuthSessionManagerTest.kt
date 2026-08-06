package com.vault999.android.auth

import com.vault999.android.model.Account
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionManagerTest {
    @Test fun `startup proactively refreshes inside the sixty second safety window`() = runBlocking {
        val store = MemorySessionStore(session(expires = 160))
        val transport = FakeTransport().apply {
            refreshResult = AccountTransportResult.Success(session(access = "rotated", expires = 1_000_000))
        }
        val manager = AuthSessionManager(store, transport) { 100 }

        assertTrue(manager.restoreAtStartup() is AccountProjection.SignedIn)
        assertEquals(1, transport.refreshCalls.get())
    }

    @Test fun `startup refreshes an expired access token instead of dropping login`() = runBlocking {
        val store = MemorySessionStore(session(expires = 99))
        val transport = FakeTransport().apply { refreshResult = AccountTransportResult.Success(session(access = "rotated", expires = 1_000_000)) }
        val manager = AuthSessionManager(store, transport) { 100 }

        assertEquals(AccountAvailability.ONLINE, (manager.restoreAtStartup() as AccountProjection.SignedIn).availability)
        assertEquals(1, transport.refreshCalls.get())
        assertEquals(1, store.writeCount)
        val access = (manager.accessSession() as SessionAccess.Granted).session
        access.useAccessToken { it.use { token -> assertEquals("rotated", token) } }
    }

    @Test fun `transient startup refresh keeps cached identity and encrypted session`() = runBlocking {
        val store = MemorySessionStore(session(expires = 99))
        val transport = FakeTransport().apply { refreshResult = AccountTransportResult.TransientFailure("offline") }
        val manager = AuthSessionManager(store, transport) { 100 }

        val projection = manager.restoreAtStartup() as AccountProjection.SignedIn
        assertEquals(AccountAvailability.OFFLINE_CACHED, projection.availability)
        assertTrue(projection.account.cached)
        assertEquals(0, store.clearCount)
        assertTrue(manager.accessSession() is SessionAccess.TemporarilyUnavailable)
    }

    @Test fun `explicit refresh rejection clears credentials`() = runBlocking {
        val store = MemorySessionStore(session(expires = 99))
        val transport = FakeTransport().apply { refreshResult = AccountTransportResult.AuthenticationRejected }
        val manager = AuthSessionManager(store, transport) { 100 }

        assertEquals(AccountProjection.SignedOut, manager.restoreAtStartup())
        assertEquals(1, store.clearCount)
        assertEquals(SessionAccess.SignedOut, manager.accessSession())
    }

    @Test fun `corruption clears while a missing session does not perform deletion`() = runBlocking {
        val corrupt = MemorySessionStore(readResult = SessionReadResult.Corrupt)
        assertEquals(AccountProjection.SignedOut, AuthSessionManager(corrupt, FakeTransport()) { 100 }.restoreAtStartup())
        assertEquals(1, corrupt.clearCount)

        val missing = MemorySessionStore(readResult = SessionReadResult.Missing)
        AuthSessionManager(missing, FakeTransport()) { 100 }.restoreAtStartup()
        assertEquals(0, missing.clearCount)
    }

    @Test fun `concurrent callers share exactly one refresh operation`() = runBlocking {
        val clock = AtomicLong(100)
        val store = MemorySessionStore(session(expires = 100_000))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply {
            refreshBlock = {
                entered.complete(Unit)
                release.await()
                AccountTransportResult.Success(session(access = "shared", expires = 1_000_000))
            }
        }
        val manager = AuthSessionManager(store, transport, clock::get)
        manager.restoreAtStartup()
        clock.set(200_000)

        val calls = (1..12).map { async(Dispatchers.Default) { manager.accessSession() } }
        entered.await()
        release.complete(Unit)
        val results = calls.awaitAll()

        assertTrue(results.all { it is SessionAccess.Granted })
        assertEquals(1, transport.refreshCalls.get())
    }

    @Test fun `concurrent authenticated 401 responses share one forced refresh`() = runBlocking {
        val store = MemorySessionStore(session(expires = 100_000))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply {
            refreshBlock = {
                entered.complete(Unit)
                release.await()
                AccountTransportResult.Success(session(access = "after-401", expires = 200_000))
            }
        }
        val manager = AuthSessionManager(store, transport) { 100 }
        manager.restoreAtStartup()
        val rejected = (manager.accessSession() as SessionAccess.Granted).session

        val calls = (1..8).map { async(Dispatchers.Default) { manager.refreshAfterUnauthorized(rejected) } }
        entered.await()
        release.complete(Unit)

        assertTrue(calls.awaitAll().all { it is SessionAccess.Granted })
        assertEquals(1, transport.refreshCalls.get())
    }

    @Test fun `concurrent callers also share one transient refresh failure`() = runBlocking {
        val clock = AtomicLong(100)
        val store = MemorySessionStore(session(expires = 100_000))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply {
            refreshBlock = {
                entered.complete(Unit)
                release.await()
                AccountTransportResult.TransientFailure("offline")
            }
        }
        val manager = AuthSessionManager(store, transport, clock::get)
        manager.restoreAtStartup()
        clock.set(200_000)

        val calls = (1..12).map { async(Dispatchers.Default) { manager.accessSession() } }
        entered.await()
        release.complete(Unit)

        assertTrue(calls.awaitAll().all { it is SessionAccess.TemporarilyUnavailable })
        assertEquals(1, transport.refreshCalls.get())
        assertEquals(0, store.clearCount)
    }

    @Test fun `logout during refresh cannot resurrect the completed session`() = runBlocking {
        val clock = AtomicLong(100)
        val store = MemorySessionStore(session(expires = 100_000))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply {
            refreshBlock = {
                entered.complete(Unit)
                release.await()
                AccountTransportResult.Success(session(access = "must-not-return", expires = 1_000_000))
            }
        }
        val manager = AuthSessionManager(store, transport, clock::get)
        manager.restoreAtStartup()
        clock.set(200_000)
        val access = async(Dispatchers.Default) { manager.accessSession() }
        entered.await()

        manager.logout()
        release.complete(Unit)

        assertEquals(SessionAccess.SignedOut, access.await())
        assertEquals(AccountProjection.SignedOut, manager.projection())
        assertEquals(1, store.clearCount)
    }

    @Test fun `logout clears before a failed revocation and projections never stringify credentials`() = runBlocking {
        val store = MemorySessionStore(session(expires = 1_000))
        val transport = FakeTransport().apply { throwOnRevoke = true }
        val manager = AuthSessionManager(store, transport) { 100 }
        val projection = manager.restoreAtStartup()

        assertFalse(projection.toString().contains("access-secret"))
        assertFalse(store.current.toString().contains("access-secret"))
        runCatching { manager.logout() }
        assertEquals(AccountProjection.SignedOut, manager.projection())
        assertEquals(1, store.clearCount)
    }

    @Test fun `failed store clear cannot resurrect a logged out session`() = runBlocking {
        val store = MemorySessionStore(session(expires = 1_000)).apply { failClear = true }
        val manager = AuthSessionManager(store, FakeTransport()) { 100 }
        manager.restoreAtStartup()

        manager.logout()

        assertEquals(AccountProjection.SignedOut, manager.restoreAtStartup())
        assertEquals(SessionAccess.SignedOut, manager.accessSession())
        assertTrue(store.nextRead is SessionReadResult.Loaded)
    }

    @Test fun `accepted callback capability can be exchanged only once concurrently`() = runBlocking {
        val pending = PendingAuthorization("state", OpaqueSecret.from("verifier"), "challenge", 1_000)
        val machine = AuthorizationStateMachine { pending }
        machine.begin(1_000)
        val accepted = machine.consume(
            "vault999://auth/callback?ticket=one-use&state=state",
            2_000,
        ) as CallbackConsumption.Accepted
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply {
            exchangeBlock = {
                entered.complete(Unit)
                release.await()
                AccountTransportResult.Success(session(expires = 1_000_000))
            }
        }
        val manager = AuthSessionManager(MemorySessionStore(), transport) { 100 }

        val first = async(Dispatchers.Default) { manager.completeSignIn(accepted) }
        entered.await()
        val replay = async(Dispatchers.Default) { manager.completeSignIn(accepted) }.await()
        release.complete(Unit)

        assertEquals(AccountTransportResult.AuthenticationRejected, replay)
        assertTrue(first.await() is AccountTransportResult.Success)
        assertEquals(1, transport.exchangeCalls.get())
    }

    private fun session(access: String = "access-secret", expires: Long) = AccountSession.create(
        account = Account("account-1", "Listener"),
        accessToken = access,
        refreshToken = "refresh-secret",
        accessExpiresAtEpochMs = expires,
    )
}

private class MemorySessionStore(initial: AccountSession? = null, readResult: SessionReadResult? = null) : SessionStore {
    var current: AccountSession? = initial
    var nextRead: SessionReadResult = readResult ?: initial?.let(SessionReadResult::Loaded) ?: SessionReadResult.Missing
    var writeCount = 0
    var clearCount = 0
    var failClear = false

    override fun read(): SessionReadResult = nextRead
    override fun write(session: AccountSession, writtenAtEpochMs: Long) {
        current = session
        nextRead = SessionReadResult.Loaded(session)
        writeCount++
    }
    override fun clear() {
        clearCount++
        if (failClear) throw IOException("test failure")
        current = null
        nextRead = SessionReadResult.Missing
    }
}

private class FakeTransport : AccountSessionTransport {
    var refreshResult: AccountTransportResult<AccountSession> = AccountTransportResult.TransientFailure("offline")
    var refreshBlock: (suspend () -> AccountTransportResult<AccountSession>)? = null
    var exchangeBlock: (suspend () -> AccountTransportResult<AccountSession>)? = null
    var throwOnRevoke = false
    val refreshCalls = AtomicInteger()
    val exchangeCalls = AtomicInteger()

    override suspend fun exchange(
        ticket: OpaqueSecret,
        state: OpaqueSecret,
        verifier: OpaqueSecret,
        redirectUri: String,
    ): AccountTransportResult<AccountSession> {
        require(redirectUri == AuthCallbackParser.REDIRECT_URI)
        exchangeCalls.incrementAndGet()
        return exchangeBlock?.invoke() ?: AccountTransportResult.TransientFailure("not_configured")
    }

    override suspend fun refresh(refreshToken: OpaqueSecret): AccountTransportResult<AccountSession> {
        refreshCalls.incrementAndGet()
        return refreshBlock?.invoke() ?: refreshResult
    }

    override suspend fun revoke(accessToken: OpaqueSecret): AccountTransportResult<Unit> {
        if (throwOnRevoke) error("network failed")
        return AccountTransportResult.Success(Unit)
    }

    override suspend fun account(accessToken: OpaqueSecret): AccountTransportResult<Account> =
        AccountTransportResult.TransientFailure("not_configured")
}
