package com.vault999.android.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext

data class ZipSafetyLimits(
    val maxEntries: Long = 100_000,
    val maxEntryBytes: Long = 8L * 1024 * 1024 * 1024,
    val maxTotalBytes: Long = 64L * 1024 * 1024 * 1024,
    val maxCompressionRatio: Double = 250.0,
) {
    init {
        require(maxEntries > 0)
        require(maxEntryBytes > 0)
        require(maxTotalBytes > 0)
        require(maxCompressionRatio >= 1.0 && maxCompressionRatio.isFinite())
    }
}

data class ZipEntryPlan(
    val originalName: String,
    val outputPath: VaultPath,
    val directory: Boolean,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val crc32: Long,
)

data class ZipArchivePlan(
    val entries: List<ZipEntryPlan>,
    val totalUncompressedBytes: Long,
    val zip64: Boolean,
)

data class ExtractionCheckpoint(
    val entryName: String,
    val entryIndex: Int,
    val entryBytes: Long,
    val totalExtractedBytes: Long,
    val entryComplete: Boolean,
)

data class ExtractionResult(
    val extractedEntries: Int,
    val skippedEntries: Int,
    val extractedBytes: Long,
    val zip64: Boolean,
)

class UnsafeArchiveException(message: String) : IOException(message)

/**
 * Validates central-directory metadata (including Zip64 and Unix link mode) before any output is
 * opened, then delegates decompression to [ZipFile] and copies through a fixed-size buffer.
 */
class SafeZipExtractor(
    private val limits: ZipSafetyLimits = ZipSafetyLimits(),
    private val bufferBytes: Int = 64 * 1024,
    private val checkpointBytes: Long = 1024L * 1024,
    private val entrySourceFactory: (ZipFile, ZipEntry) -> InputStream = ZipFile::getInputStream,
) {
    init {
        require(bufferBytes in 8 * 1024..1024 * 1024)
        require(checkpointBytes > 0)
    }

    fun inspect(archive: File): ZipArchivePlan = CentralDirectoryReader(archive, limits).read()

    suspend fun extract(
        archive: File,
        destination: VaultStorage,
        completedEntries: Set<String> = emptySet(),
        onCheckpoint: suspend (ExtractionCheckpoint) -> Unit = {},
    ): ExtractionResult = withContext(Dispatchers.IO) {
        require(archive.isFile) { "Archive does not exist" }
        if (java.nio.file.Files.isSymbolicLink(archive.toPath())) throw UnsafeArchiveException("Archive cannot be a symbolic link")
        val plan = inspect(archive)
        var extracted = 0
        var skipped = 0
        var totalWritten = 0L
        ZipFile(archive).use { zip ->
            val byName = zip.entries().asSequence().associateBy { it.name }
            if (byName.size != plan.entries.size) throw UnsafeArchiveException("ZIP entry list changed after validation")
            plan.entries.forEachIndexed { index, entryPlan ->
                coroutineContext.ensureActive()
                val zipEntry = byName[entryPlan.originalName]
                    ?: throw UnsafeArchiveException("ZIP entry is missing after validation")
                if (zipEntry.isDirectory != entryPlan.directory) throw UnsafeArchiveException("ZIP entry type changed after validation")
                if (zipEntry.crc != entryPlan.crc32) throw UnsafeArchiveException("ZIP entry CRC changed after validation")
                if (entryPlan.directory) {
                    destination.createDirectories(entryPlan.outputPath)
                    onCheckpoint(ExtractionCheckpoint(entryPlan.originalName, index, 0, totalWritten, true))
                    return@forEachIndexed
                }
                val existing = destination.inspect(entryPlan.outputPath)
                if (entryPlan.originalName in completedEntries && existing.exists && !existing.isDirectory &&
                    existing.size == entryPlan.uncompressedBytes &&
                    existingCrcMatches(destination, entryPlan.outputPath, entryPlan.uncompressedBytes, entryPlan.crc32)
                ) {
                    skipped++
                    totalWritten = checkedAdd(totalWritten, entryPlan.uncompressedBytes)
                    onCheckpoint(
                        ExtractionCheckpoint(entryPlan.originalName, index, entryPlan.uncompressedBytes, totalWritten, true),
                    )
                    return@forEachIndexed
                }
                val partial = partialPath(entryPlan.outputPath)
                destination.delete(partial)
                try {
                    val copy = copyEntry(zip, zipEntry, entryPlan, destination, partial, index, totalWritten, onCheckpoint)
                    val entryWritten = copy.bytes
                    if (entryWritten != entryPlan.uncompressedBytes) {
                        throw UnsafeArchiveException("Entry length differs from central-directory metadata")
                    }
                    if (copy.crc32 != entryPlan.crc32) {
                        throw UnsafeArchiveException("Entry CRC differs from central-directory metadata")
                    }
                    destination.move(partial, entryPlan.outputPath, replaceExisting = true)
                    extracted++
                    totalWritten = checkedAdd(totalWritten, entryWritten)
                    if (totalWritten > limits.maxTotalBytes) throw UnsafeArchiveException("Archive exceeded total extraction limit")
                    onCheckpoint(ExtractionCheckpoint(entryPlan.originalName, index, entryWritten, totalWritten, true))
                } catch (failure: Throwable) {
                    destination.delete(partial)
                    throw failure
                }
            }
        }
        ExtractionResult(extracted, skipped, totalWritten, plan.zip64)
    }

    private suspend fun copyEntry(
        zip: ZipFile,
        zipEntry: ZipEntry,
        plan: ZipEntryPlan,
        destination: VaultStorage,
        partial: VaultPath,
        index: Int,
        totalWritten: Long,
        onCheckpoint: suspend (ExtractionCheckpoint) -> Unit,
    ): EntryCopy = coroutineScope {
        val active = ActiveExtractionResources()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                active.closeAll()
            }
        }
        try {
            val source = entrySourceFactory(zip, zipEntry).also(active::add)
            val sink = destination.openSink(partial, 0).also(active::add)
            val crc = CRC32()
            val buffer = ByteArray(bufferBytes)
            var entryWritten = 0L
            var nextCheckpoint = checkpointBytes
            while (true) {
                coroutineContext.ensureActive()
                val count = try {
                    source.read(buffer)
                } catch (failure: IOException) {
                    coroutineContext.ensureActive()
                    throw failure
                }
                coroutineContext.ensureActive()
                if (count < 0) break
                if (count == 0) continue
                try {
                    sink.write(buffer, 0, count)
                } catch (failure: IOException) {
                    coroutineContext.ensureActive()
                    throw failure
                }
                coroutineContext.ensureActive()
                crc.update(buffer, 0, count)
                entryWritten = checkedAdd(entryWritten, count.toLong())
                if (entryWritten > plan.uncompressedBytes || entryWritten > limits.maxEntryBytes) {
                    throw UnsafeArchiveException("Entry expanded beyond its declared size")
                }
                if (entryWritten >= nextCheckpoint) {
                    sink.flush()
                    onCheckpoint(
                        ExtractionCheckpoint(
                            plan.originalName,
                            index,
                            entryWritten,
                            checkedAdd(totalWritten, entryWritten),
                            false,
                        ),
                    )
                    nextCheckpoint = saturatingAdd(entryWritten, checkpointBytes)
                }
            }
            sink.flush()
            EntryCopy(entryWritten, crc.value)
        } finally {
            watcher.cancel()
            active.closeAll()
        }
    }

    private suspend fun existingCrcMatches(
        destination: VaultStorage,
        path: VaultPath,
        expectedBytes: Long,
        expectedCrc: Long,
    ): Boolean = coroutineScope {
        val active = ActiveExtractionResources()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                active.closeAll()
            }
        }
        try {
            val source = destination.openSource(path).also(active::add)
            val crc = CRC32()
            val buffer = ByteArray(bufferBytes)
            var read = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = try {
                    source.read(buffer)
                } catch (failure: IOException) {
                    coroutineContext.ensureActive()
                    throw failure
                }
                coroutineContext.ensureActive()
                if (count < 0) break
                if (count == 0) continue
                crc.update(buffer, 0, count)
                read = checkedAdd(read, count.toLong())
                if (read > expectedBytes) return@coroutineScope false
            }
            read == expectedBytes && crc.value == expectedCrc
        } finally {
            watcher.cancel()
            active.closeAll()
        }
    }

    private fun partialPath(finalPath: VaultPath): VaultPath {
        val segments = finalPath.segments.toMutableList()
        segments[segments.lastIndex] = ".${segments.last()}.vault-part"
        return VaultPath.of(segments.joinToString("/"))
    }

    private fun checkedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw UnsafeArchiveException("Archive size overflow")
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private data class EntryCopy(val bytes: Long, val crc32: Long)

    private class ActiveExtractionResources {
        private val resources = mutableListOf<Closeable>()

        @Synchronized fun add(resource: Closeable) {
            resources += resource
        }

        @Synchronized fun closeAll() {
            resources.asReversed().forEach { runCatching { it.close() } }
            resources.clear()
        }
    }
}

private class CentralDirectoryReader(
    private val archive: File,
    private val limits: ZipSafetyLimits,
) {
    fun read(): ZipArchivePlan = RandomAccessFile(archive, "r").use { file ->
        val eocdOffset = findEocd(file)
        file.seek(eocdOffset)
        requireSignature(file, EOCD_SIGNATURE)
        val disk = file.readUShortLE()
        val centralDisk = file.readUShortLE()
        val entriesOnDisk = file.readUShortLE().toLong()
        val legacyEntries = file.readUShortLE().toLong()
        val legacySize = file.readUIntLE()
        val legacyOffset = file.readUIntLE()
        if (disk != 0 || centralDisk != 0 || entriesOnDisk != legacyEntries) {
            throw UnsafeArchiveException("Multi-disk ZIP archives are not supported")
        }
        val needsZip64 = legacyEntries == USHORT_MAX || legacySize == UINT_MAX || legacyOffset == UINT_MAX
        val directory = if (needsZip64) readZip64(file, eocdOffset) else DirectoryLocation(legacyEntries, legacySize, legacyOffset)
        if (directory.entries > limits.maxEntries) throw UnsafeArchiveException("Archive has too many entries")
        if (directory.entries > Int.MAX_VALUE) throw UnsafeArchiveException("Archive entry count exceeds platform limits")
        val directoryEnd = checkedAdd(directory.offset, directory.size)
        if (directory.offset < 0 || directoryEnd > file.length() || directoryEnd > eocdOffset) {
            throw UnsafeArchiveException("Central directory lies outside the archive")
        }
        file.seek(directory.offset)
        val plans = ArrayList<ZipEntryPlan>(directory.entries.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val outputNames = HashSet<String>()
        var total = 0L
        repeat(directory.entries.toInt()) {
            requireSignature(file, CENTRAL_SIGNATURE)
            val versionMadeBy = file.readUShortLE()
            file.readUShortLE() // version needed
            val flags = file.readUShortLE()
            file.readUShortLE() // compression method
            file.skipExact(4) // modification time/date
            val crc32 = file.readUIntLE()
            var compressed = file.readUIntLE()
            var uncompressed = file.readUIntLE()
            val nameLength = file.readUShortLE()
            val extraLength = file.readUShortLE()
            val commentLength = file.readUShortLE()
            val diskStart = file.readUShortLE().toLong()
            file.readUShortLE() // internal attributes
            val externalAttributes = file.readUIntLE()
            var localOffset = file.readUIntLE()
            val nameBytes = ByteArray(nameLength).also(file::readFully)
            val extra = ByteArray(extraLength).also(file::readFully)
            file.skipExact(commentLength.toLong())
            if (flags and 1 != 0) throw UnsafeArchiveException("Encrypted ZIP entries are not supported")
            val zip64 = readZip64Extra(extra, uncompressed, compressed, localOffset, diskStart)
            uncompressed = zip64.uncompressed
            compressed = zip64.compressed
            localOffset = zip64.localOffset
            if (zip64.diskStart != 0L || localOffset >= directory.offset) {
                throw UnsafeArchiveException("Invalid local entry location")
            }
            val name = nameBytes.toString(if (flags and UTF8_FLAG != 0) Charsets.UTF_8 else CP437)
            if (name.indexOf('\u0000') >= 0) throw UnsafeArchiveException("NUL in ZIP entry name")
            val directoryEntry = name.endsWith('/') || name.endsWith('\\')
            val creatorSystem = versionMadeBy ushr 8
            val unixMode = (externalAttributes ushr 16).toInt()
            if (creatorSystem == UNIX_CREATOR && unixMode and FILE_TYPE_MASK == SYMBOLIC_LINK) {
                throw UnsafeArchiveException("Symbolic link entry rejected")
            }
            if (uncompressed > limits.maxEntryBytes) throw UnsafeArchiveException("ZIP entry exceeds size limit")
            if (!directoryEntry && uncompressed > 0 && compressed == 0L) {
                throw UnsafeArchiveException("ZIP entry has an infinite compression ratio")
            }
            if (compressed > 0 && uncompressed.toDouble() / compressed.toDouble() > limits.maxCompressionRatio) {
                throw UnsafeArchiveException("ZIP entry exceeds compression-ratio limit")
            }
            total = checkedAdd(total, uncompressed)
            if (total > limits.maxTotalBytes) throw UnsafeArchiveException("Archive exceeds total size limit")
            val output = try {
                VaultPath.of(name)
            } catch (failure: IllegalArgumentException) {
                throw UnsafeArchiveException("Unsafe ZIP entry path: ${failure.message}")
            }
            if (!outputNames.add(output.value)) throw UnsafeArchiveException("ZIP entries collide after path sanitization")
            plans += ZipEntryPlan(name, output, directoryEntry, compressed, uncompressed, crc32)
        }
        if (file.filePointer != directoryEnd) throw UnsafeArchiveException("Central-directory length mismatch")
        ZipArchivePlan(plans, total, needsZip64)
    }

    private fun findEocd(file: RandomAccessFile): Long {
        if (file.length() < EOCD_MIN_SIZE) throw ZipException("End-of-central-directory record is missing")
        val searchStart = (file.length() - EOCD_MAX_SEARCH).coerceAtLeast(0)
        var position = file.length() - EOCD_MIN_SIZE
        while (position >= searchStart) {
            file.seek(position)
            if (file.readUIntLE() == EOCD_SIGNATURE) {
                file.skipExact(16)
                val commentLength = file.readUShortLE()
                if (position + EOCD_MIN_SIZE + commentLength == file.length()) return position
            }
            position--
        }
        throw ZipException("End-of-central-directory record is missing")
    }

    private fun readZip64(file: RandomAccessFile, eocdOffset: Long): DirectoryLocation {
        val locatorOffset = eocdOffset - ZIP64_LOCATOR_SIZE
        if (locatorOffset < 0) throw ZipException("Zip64 locator is missing")
        file.seek(locatorOffset)
        requireSignature(file, ZIP64_LOCATOR_SIGNATURE)
        if (file.readUIntLE() != 0L) throw UnsafeArchiveException("Multi-disk Zip64 is not supported")
        val zip64Offset = file.readLongLE()
        if (file.readUIntLE() != 1L) throw UnsafeArchiveException("Multi-disk Zip64 is not supported")
        if (zip64Offset < 0 || zip64Offset >= locatorOffset) throw UnsafeArchiveException("Invalid Zip64 directory location")
        file.seek(zip64Offset)
        requireSignature(file, ZIP64_EOCD_SIGNATURE)
        val recordSize = file.readLongLE()
        if (recordSize < ZIP64_EOCD_BODY_MIN) throw ZipException("Zip64 end record is truncated")
        file.skipExact(4) // versions
        if (file.readUIntLE() != 0L || file.readUIntLE() != 0L) throw UnsafeArchiveException("Multi-disk Zip64 is not supported")
        val entriesOnDisk = file.readLongLE()
        val entries = file.readLongLE()
        if (entries < 0 || entriesOnDisk != entries) throw UnsafeArchiveException("Invalid Zip64 entry count")
        val size = file.readLongLE()
        val offset = file.readLongLE()
        return DirectoryLocation(entries, size, offset)
    }

    private fun readZip64Extra(
        extra: ByteArray,
        uncompressed32: Long,
        compressed32: Long,
        offset32: Long,
        disk32: Long,
    ): Zip64Values {
        var uncompressed = uncompressed32
        var compressed = compressed32
        var offset = offset32
        var disk = disk32
        if (listOf(uncompressed, compressed, offset).none { it == UINT_MAX } && disk != USHORT_MAX) {
            return Zip64Values(uncompressed, compressed, offset, disk)
        }
        var cursor = 0
        while (cursor + 4 <= extra.size) {
            val header = extra.uShort(cursor)
            val length = extra.uShort(cursor + 2)
            cursor += 4
            if (cursor + length > extra.size) throw ZipException("Truncated ZIP extra field")
            if (header == ZIP64_EXTRA_ID) {
                var valueCursor = cursor
                fun longValue(): Long {
                    if (valueCursor + 8 > cursor + length) throw ZipException("Truncated Zip64 extra field")
                    val value = extra.longLE(valueCursor)
                    valueCursor += 8
                    if (value < 0) throw UnsafeArchiveException("Zip64 value exceeds signed range")
                    return value
                }
                if (uncompressed == UINT_MAX) uncompressed = longValue()
                if (compressed == UINT_MAX) compressed = longValue()
                if (offset == UINT_MAX) offset = longValue()
                if (disk == USHORT_MAX) disk = longValue()
                return Zip64Values(uncompressed, compressed, offset, disk)
            }
            cursor += length
        }
        throw ZipException("Required Zip64 extra field is missing")
    }

    private fun requireSignature(file: RandomAccessFile, expected: Long) {
        if (file.readUIntLE() != expected) throw ZipException("Invalid ZIP record signature")
    }

    private fun checkedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw UnsafeArchiveException("ZIP offset overflow")
    }

    private data class DirectoryLocation(val entries: Long, val size: Long, val offset: Long)
    private data class Zip64Values(val uncompressed: Long, val compressed: Long, val localOffset: Long, val diskStart: Long)

    companion object {
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
        private const val EOCD_MIN_SIZE = 22L
        private const val EOCD_MAX_SEARCH = 65_557L
        private const val ZIP64_LOCATOR_SIZE = 20L
        private const val ZIP64_EOCD_BODY_MIN = 44L
        private const val UINT_MAX = 0xffff_ffffL
        private const val USHORT_MAX = 0xffffL
        private const val UTF8_FLAG = 1 shl 11
        private const val UNIX_CREATOR = 3
        private const val FILE_TYPE_MASK = 0xf000
        private const val SYMBOLIC_LINK = 0xa000
        private const val ZIP64_EXTRA_ID = 0x0001
        private val CP437: Charset = Charset.forName("Cp437")
    }
}

private fun RandomAccessFile.readUShortLE(): Int {
    val low = read()
    val high = read()
    if (low < 0 || high < 0) throw EOFException()
    return low or (high shl 8)
}

private fun RandomAccessFile.readUIntLE(): Long {
    var value = 0L
    repeat(4) { index ->
        val byte = read()
        if (byte < 0) throw EOFException()
        value = value or (byte.toLong() shl (index * 8))
    }
    return value
}

private fun RandomAccessFile.readLongLE(): Long {
    var value = 0L
    repeat(8) { index ->
        val byte = read()
        if (byte < 0) throw EOFException()
        value = value or (byte.toLong() shl (index * 8))
    }
    return value
}

private fun RandomAccessFile.skipExact(bytes: Long) {
    if (bytes < 0 || filePointer + bytes > length()) throw EOFException()
    seek(filePointer + bytes)
}

private fun ByteArray.uShort(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.longLE(offset: Int): Long {
    var value = 0L
    repeat(8) { index -> value = value or ((this[offset + index].toLong() and 0xff) shl (index * 8)) }
    return value
}
