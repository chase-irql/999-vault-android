package com.vault999.android.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test fun `creates unique S256 material and consumes exact callback`() {
        val first = Pkce.create(1000)
        val second = Pkce.create(1000)
        assertNotEquals(first.state, second.state)
        first.verifier.use { verifier ->
            val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
            )
            assertEquals(expected, first.challenge)
        }
        val callback = AuthCallbackParser.consume("vault999://auth/callback?ticket=one-use&state=${first.state}", first, 2000)
        callback.ticket.use { assertEquals("one-use", it) }
    }

    @Test(expected = IllegalArgumentException::class) fun `rejects duplicate callback fields`() {
        val pending = Pkce.create(1000)
        AuthCallbackParser.consume("vault999://auth/callback?ticket=a&ticket=b&state=${pending.state}", pending, 2000)
    }

    @Test(expected = IllegalArgumentException::class) fun `rejects expired callback`() {
        val pending = Pkce.create(1000)
        AuthCallbackParser.consume("vault999://auth/callback?ticket=a&state=${pending.state}", pending, 1_000 + 11 * 60 * 1000)
    }

    @Test fun `state machine consumes valid callback exactly once`() {
        val pending = PendingAuthorization("expected", OpaqueSecret.from("verifier-secret"), "challenge", 1_000)
        val machine = AuthorizationStateMachine { pending }
        machine.begin(1_000)

        val first = machine.consume("vault999://auth/callback?ticket=one-use&state=expected", 2_000)
        assertTrue(first is CallbackConsumption.Accepted)
        assertEquals(CallbackConsumption.Rejected.NO_PENDING_ATTEMPT, machine.consume("vault999://auth/callback?ticket=one-use&state=expected", 2_000))
        assertFalse(machine.hasPendingAttempt())
        assertFalse(first.toString().contains("verifier-secret"))
        assertFalse(first.toString().contains("one-use"))
    }

    @Test fun `persisted pending authorization survives process death and is durably consumed`() {
        val store = MemoryPendingAuthorizationStore()
        val pending = PendingAuthorization("persisted", OpaqueSecret.from("verifier-secret"), "challenge", 1_000)
        AuthorizationStateMachine(store) { pending }.begin(1_000)

        val afterRestart = AuthorizationStateMachine(store)
        assertTrue(
            afterRestart.consume("vault999://auth/callback?ticket=one-use&state=persisted", 2_000) is
                CallbackConsumption.Accepted,
        )
        assertEquals(
            CallbackConsumption.Rejected.NO_PENDING_ATTEMPT,
            AuthorizationStateMachine(store).consume(
                "vault999://auth/callback?ticket=one-use&state=persisted",
                2_000,
            ),
        )
    }

    @Test fun `callback is not accepted when durable consumption fails`() {
        val store = MemoryPendingAuthorizationStore(failClear = true)
        val pending = PendingAuthorization("persisted", OpaqueSecret.from("verifier-secret"), "challenge", 1_000)
        AuthorizationStateMachine(store) { pending }.begin(1_000)

        assertEquals(
            CallbackConsumption.Rejected.STORAGE_FAILURE,
            AuthorizationStateMachine(store).consume(
                "vault999://auth/callback?ticket=one-use&state=persisted",
                2_000,
            ),
        )
    }

    @Test fun `invalid callback does not consume a valid pending browser attempt`() {
        val pending = PendingAuthorization("expected", OpaqueSecret.from("verifier"), "challenge", 1_000)
        val machine = AuthorizationStateMachine { pending }
        machine.begin(1_000)

        assertEquals(CallbackConsumption.Rejected.INVALID_CALLBACK, machine.consume("vault999://auth/callback?ticket=x&state=wrong", 2_000))
        assertTrue(machine.hasPendingAttempt())
        assertTrue(machine.consume("vault999://auth/callback?ticket=x&state=expected", 2_000) is CallbackConsumption.Accepted)
    }

    @Test fun `expiry and cancellation erase pending verifier`() {
        val pending = PendingAuthorization("expected", OpaqueSecret.from("verifier"), "challenge", 1_000)
        val machine = AuthorizationStateMachine { pending }
        machine.begin(1_000)
        assertEquals(
            CallbackConsumption.Rejected.EXPIRED,
            machine.consume("vault999://auth/callback?ticket=x&state=expected", 1_000 + AuthCallbackParser.MAX_AGE_MS + 1),
        )
        assertFalse(machine.hasPendingAttempt())

        machine.begin(2_000)
        machine.cancel()
        assertFalse(machine.hasPendingAttempt())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects callback authority variations`() {
        val pending = PendingAuthorization("expected", OpaqueSecret.from("verifier"), "challenge", 1_000)
        AuthCallbackParser.consume("vault999://auth:443/callback?ticket=x&state=expected", pending, 2_000)
    }
}

private class MemoryPendingAuthorizationStore(
    private val failClear: Boolean = false,
) : PendingAuthorizationStore {
    private var pending: PendingAuthorization? = null
    override fun read(): PendingAuthorization? = pending
    override fun write(pending: PendingAuthorization) { this.pending = pending }
    override fun clear() {
        if (failClear) throw IOException("test failure")
        pending = null
    }
}
