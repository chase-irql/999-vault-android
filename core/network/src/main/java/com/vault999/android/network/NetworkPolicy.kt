package com.vault999.android.network

import com.vault999.android.model.VaultError
import kotlinx.coroutines.delay
import okhttp3.Headers
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class RetryPolicy(
    val maxAttempts: Int = 4,
    val initialDelayMs: Long = 750,
    val maximumDelayMs: Long = 10_000,
    val maximumRetryAfterMs: Long = 30_000,
) {
    init {
        require(maxAttempts in 1..6)
        require(initialDelayMs in 0..maximumDelayMs)
        require(maximumDelayMs in 0..60_000)
        require(maximumRetryAfterMs in 0..60_000)
    }

    fun delayMs(attempt: Int, headers: Headers?, now: Instant): Long {
        parseRetryAfter(headers?.get("Retry-After"), now)?.let { return it.coerceAtMost(maximumRetryAfterMs) }
        val exponent = (attempt - 1).coerceIn(0, 10)
        return (initialDelayMs * (1L shl exponent)).coerceAtMost(maximumDelayMs)
    }

    private fun parseRetryAfter(value: String?, now: Instant): Long? {
        val input = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        input.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
            return if (seconds > Long.MAX_VALUE / 1_000) maximumRetryAfterMs else seconds * 1_000
        }
        return runCatching {
            val target = ZonedDateTime.parse(input, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (target.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0)
        }.getOrNull()
    }
}

fun interface RetryDelay {
    suspend fun wait(milliseconds: Long)

    companion object {
        val DEFAULT = RetryDelay { milliseconds -> delay(milliseconds) }
    }
}

class NetworkException(
    val error: VaultError,
    val routeTemplate: String,
    val statusCode: Int? = null,
) : Exception(publicMessage(error)) {
    companion object {
        private fun publicMessage(error: VaultError): String = when (error) {
            is VaultError.Offline -> "Unable to reach the archive."
            is VaultError.Timeout -> "The archive request timed out."
            is VaultError.RateLimited -> "The archive is temporarily rate limiting requests."
            is VaultError.Server -> "The archive returned a server error."
            is VaultError.Validation -> "The archive returned an invalid response."
            else -> "The operation could not be completed."
        }
    }
}
