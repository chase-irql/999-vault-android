package com.vault999.android.downloads

sealed interface DiskSpaceDecision {
    data class Sufficient(val availableBytes: Long, val requiredBytes: Long) : DiskSpaceDecision
    data class Insufficient(val availableBytes: Long, val requiredBytes: Long) : DiskSpaceDecision
    data class Unknown(val requiredBytes: Long) : DiskSpaceDecision
}

class DiskSpacePolicy(
    private val reserveBytes: Long = 128L * 1024 * 1024,
) {
    init { require(reserveBytes >= 0) }

    fun check(availableBytes: Long?, bytesStillToWrite: Long): DiskSpaceDecision {
        require(bytesStillToWrite >= 0)
        val required = saturatingAdd(bytesStillToWrite, reserveBytes)
        if (availableBytes == null) return DiskSpaceDecision.Unknown(required)
        require(availableBytes >= 0)
        return if (availableBytes >= required) {
            DiskSpaceDecision.Sufficient(availableBytes, required)
        } else {
            DiskSpaceDecision.Insufficient(availableBytes, required)
        }
    }

    /** Required peak space when both a temporary archive and its extracted output coexist. */
    fun checkCollection(
        availableBytes: Long?,
        archiveBytesRemaining: Long,
        uncompressedBytesRemaining: Long,
    ): DiskSpaceDecision = check(availableBytes, saturatingAdd(archiveBytesRemaining, uncompressedBytesRemaining))

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
