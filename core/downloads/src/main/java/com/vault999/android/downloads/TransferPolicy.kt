package com.vault999.android.downloads

import java.text.Normalizer
import kotlin.math.roundToLong

object SafeArchivePath {
    private val invalidWindowsNames = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).flatMap { listOf("COM$it", "LPT$it") }

    fun segments(untrusted: String): List<String> {
        require(untrusted.isNotBlank()) { "Archive path is blank" }
        require(!untrusted.startsWith('/') && !untrusted.startsWith('\\')) { "Absolute path rejected" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(untrusted)) { "Drive path rejected" }
        val result = untrusted.replace('\\', '/').split('/').filter { it.isNotBlank() }.map(::sanitizeSegment)
        require(result.isNotEmpty()) { "Archive path has no safe segments" }
        return result
    }

    private fun sanitizeSegment(raw: String): String {
        require(raw != "." && raw != "..") { "Traversal rejected" }
        require(raw.none { it.code == 0 || it.code < 32 }) { "Control character rejected" }
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .trim().trimEnd('.', ' ')
        require(normalized.isNotBlank()) { "Empty filename rejected" }
        require(normalized.uppercase() !in invalidWindowsNames) { "Reserved filename rejected" }
        return normalized.take(180)
    }
}

data class ResumeMetadata(val validator: String?, val totalBytes: Long, val completedBytes: Long)
data class ResumeResponse(val status: Int, val validator: String?, val contentRangeStart: Long?, val totalBytes: Long?)

fun canResume(saved: ResumeMetadata, response: ResumeResponse): Boolean =
    saved.completedBytes > 0 &&
        response.status == 206 &&
        !saved.validator.isNullOrBlank() &&
        saved.validator == response.validator &&
        response.contentRangeStart == saved.completedBytes &&
        response.totalBytes == saved.totalBytes

class EtaEstimator(
    private val alpha: Double = 0.25,
    private val minimumSamples: Int = 3,
    private val stallResetNanos: Long = 10_000_000_000L,
) {
    private var stage = ""
    private var lastNanos = 0L
    private var lastBytes = 0L
    private var smoothedBytesPerSecond: Double? = null
    private var samples = 0

    data class Estimate(val bytesPerSecond: Long?, val etaSeconds: Long?)

    fun sample(stage: String, bytes: Long, total: Long?, nowNanos: Long): Estimate {
        if (this.stage != stage || lastNanos == 0L || nowNanos - lastNanos > stallResetNanos || bytes < lastBytes) {
            reset(stage, bytes, nowNanos)
            return Estimate(null, null)
        }
        val elapsed = (nowNanos - lastNanos) / 1_000_000_000.0
        val delta = bytes - lastBytes
        lastNanos = nowNanos
        lastBytes = bytes
        if (elapsed <= 0 || delta <= 0) return Estimate(null, null)
        val instant = delta / elapsed
        smoothedBytesPerSecond = smoothedBytesPerSecond?.let { alpha * instant + (1 - alpha) * it } ?: instant
        samples++
        if (samples < minimumSamples) return Estimate(smoothedBytesPerSecond?.roundToLong(), null)
        val rate = smoothedBytesPerSecond?.takeIf { it > 0 } ?: return Estimate(null, null)
        val eta = total?.let { ((it - bytes).coerceAtLeast(0) / rate).roundToLong() }
        return Estimate(rate.roundToLong(), eta)
    }

    private fun reset(stage: String, bytes: Long, nowNanos: Long) {
        this.stage = stage
        lastBytes = bytes
        lastNanos = nowNanos
        smoothedBytesPerSecond = null
        samples = 0
    }
}

