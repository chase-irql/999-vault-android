package com.vault999.android.network

import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class NetworkPolicyTest {
    private val now = Instant.parse("2026-08-05T12:00:00Z")

    @Test fun `Retry-After supports seconds and HTTP dates with a safety cap`() {
        val policy = RetryPolicy(maximumRetryAfterMs = 30_000)

        assertEquals(3_000L, policy.delayMs(1, headersOf("Retry-After", "3"), now))
        assertEquals(20_000L, policy.delayMs(1, headersOf("Retry-After", "Wed, 5 Aug 2026 12:00:20 GMT"), now))
        assertEquals(30_000L, policy.delayMs(1, headersOf("Retry-After", "999999999"), now))
    }

    @Test fun `invalid Retry-After falls back to bounded exponential delays`() {
        val policy = RetryPolicy(initialDelayMs = 750, maximumDelayMs = 2_000)

        assertEquals(750L, policy.delayMs(1, headersOf("Retry-After", "not-a-date"), now))
        assertEquals(1_500L, policy.delayMs(2, null, now))
        assertEquals(2_000L, policy.delayMs(3, null, now))
    }
}
