package com.vault999.android.downloads

import okhttp3.Response

data class ContentRange(val start: Long?, val endInclusive: Long?, val total: Long?)

sealed interface RangeDecision {
    data class Fresh(val totalBytes: Long?, val validator: String?) : RangeDecision
    data class Resume(val totalBytes: Long, val validator: String) : RangeDecision
    data class Restart(val reason: String, val totalBytes: Long?, val validator: String?) : RangeDecision
    data class AlreadyComplete(val totalBytes: Long, val validator: String?) : RangeDecision
    data class Reject(val status: Int, val reason: String) : RangeDecision
}

object HttpRangeValidator {
    private val satisfied = Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)
    private val unsatisfied = Regex("^bytes \\*/(\\d+|\\*)$", RegexOption.IGNORE_CASE)

    fun parseContentRange(value: String?): ContentRange? {
        if (value == null) return null
        satisfied.matchEntire(value.trim())?.let { match ->
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
            if (end < start || (total != null && (total == 0L || end >= total))) return null
            return ContentRange(start, end, total)
        }
        unsatisfied.matchEntire(value.trim())?.let { match ->
            return ContentRange(null, null, match.groupValues[1].takeUnless { it == "*" }?.toLongOrNull())
        }
        return null
    }

    fun evaluate(saved: ResumeMetadata?, response: Response): RangeDecision {
        val validator = response.header("ETag")?.takeIf { it.isNotBlank() && !it.startsWith("W/", ignoreCase = true) }
            ?: response.header("Last-Modified")?.takeIf(String::isNotBlank)
        val range = parseContentRange(response.header("Content-Range"))
        if (saved == null || saved.completedBytes == 0L) {
            if (!response.isSuccessful) return RangeDecision.Reject(response.code, "Unexpected HTTP status")
            if (response.code == 206) {
                if (range?.start != 0L || range.endInclusive == null) {
                    return RangeDecision.Reject(response.code, "Partial response did not start at zero")
                }
                validateSatisfiedRange(range, response.body.contentLength())?.let { reason ->
                    return RangeDecision.Reject(response.code, reason)
                }
            }
            val total = range?.total ?: response.body.contentLength().takeIf { it >= 0 }
            return RangeDecision.Fresh(total, validator)
        }

        if (response.code == 416) {
            val validatorChanged = saved.validator != null && validator != null && saved.validator != validator
            return if (!validatorChanged && range?.total == saved.completedBytes && saved.totalBytes == saved.completedBytes) {
                RangeDecision.AlreadyComplete(saved.totalBytes, validator ?: saved.validator)
            } else {
                val reason = if (validatorChanged) {
                    "Range validator changed for a completed checkpoint"
                } else {
                    "Range is not satisfiable for the saved checkpoint"
                }
                RangeDecision.Restart(reason, range?.total, validator)
            }
        }
        if (response.code == 200) {
            return RangeDecision.Restart("Server ignored the Range request", response.body.contentLength().takeIf { it >= 0 }, validator)
        }
        if (response.code != 206) return RangeDecision.Reject(response.code, "Unexpected HTTP status for resume")
        if (range == null || range.start == null || range.total == null) {
            return RangeDecision.Restart("Missing or invalid Content-Range", range?.total, validator)
        }
        validateSatisfiedRange(range, response.body.contentLength())?.let { reason ->
            return RangeDecision.Restart(reason, range.total, validator)
        }
        val responseMetadata = ResumeResponse(response.code, validator, range.start, range.total)
        return if (canResume(saved, responseMetadata)) {
            RangeDecision.Resume(range.total, requireNotNull(validator))
        } else {
            RangeDecision.Restart("Range checkpoint or validator changed", range.total, validator)
        }
    }

    private fun validateSatisfiedRange(range: ContentRange, bodyLength: Long): String? {
        val start = range.start ?: return "Content-Range has no start"
        val end = range.endInclusive ?: return "Content-Range has no end"
        val span = try {
            Math.addExact(Math.subtractExact(end, start), 1L)
        } catch (_: ArithmeticException) {
            return "Content-Range length overflow"
        }
        if (range.total != null && end != range.total - 1L) {
            return "Content-Range did not cover the requested remainder"
        }
        if (bodyLength >= 0 && bodyLength != span) {
            return "Content-Length disagrees with Content-Range"
        }
        return null
    }
}
