package com.vault999.android.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeZipExtractorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `preflight rejects traversal before writing any entry`() = runBlocking {
        val archive = zip("safe/song.txt" to "safe".toByteArray(), "../escape.txt" to "escape".toByteArray())
        val root = temporary.newFolder("traversal-output")
        val failure = runCatching { SafeZipExtractor().extract(archive, AppSpecificVaultStorage(root)) }.exceptionOrNull()
        assertTrue(failure is UnsafeArchiveException)
        assertEquals(0, root.listFiles()?.size)
        assertFalse(File(root.parentFile, "escape.txt").exists())
    }

    @Test fun `preflight rejects absolute path symlink and sanitized collisions`() {
        val extractor = SafeZipExtractor()
        assertTrue(runCatching { extractor.inspect(zip("/absolute.txt" to byteArrayOf(1))) }.exceptionOrNull() is UnsafeArchiveException)
        assertTrue(
            runCatching { extractor.inspect(zip("song?.mp3" to byteArrayOf(1), "song*.mp3" to byteArrayOf(2))) }
                .exceptionOrNull() is UnsafeArchiveException,
        )
        assertTrue(runCatching { extractor.inspect(markFirstEntryAsSymlink(zip("link" to "target".toByteArray()))) }
            .exceptionOrNull() is UnsafeArchiveException)
    }

    @Test fun `large entry is streamed with bounded checkpoints and deterministic bytes`() = runBlocking {
        val expected = ByteArray(12 * 1024 * 1024)
        Random(999).nextBytes(expected)
        val archive = zip("Compilation/Large/random.bin" to expected)
        val root = temporary.newFolder("large-output")
        val checkpoints = mutableListOf<ExtractionCheckpoint>()
        val extractor = SafeZipExtractor(
            limits = ZipSafetyLimits(maxEntryBytes = 16L * 1024 * 1024, maxTotalBytes = 16L * 1024 * 1024),
            bufferBytes = 32 * 1024,
            checkpointBytes = 1024L * 1024,
        )

        val result = extractor.extract(archive, AppSpecificVaultStorage(root), onCheckpoint = checkpoints::add)

        assertEquals(1, result.extractedEntries)
        assertTrue(checkpoints.count { !it.entryComplete } >= 11)
        assertTrue(checkpoints.last().entryComplete)
        assertArrayEquals(expected, File(root, "Compilation/Large/random.bin").readBytes())
        assertFalse(File(root, "Compilation/Large/.random.bin.vault-part").exists())
    }

    @Test fun `verified completed entry is skipped and checkpointed`() = runBlocking {
        val bytes = "already here".toByteArray()
        val archive = zip("Compilation/song.txt" to bytes)
        val root = temporary.newFolder("skip-output")
        val output = File(root, "Compilation/song.txt")
        output.parentFile.mkdirs()
        output.writeBytes(bytes)
        val checkpoints = mutableListOf<ExtractionCheckpoint>()

        val result = SafeZipExtractor().extract(
            archive,
            AppSpecificVaultStorage(root),
            completedEntries = setOf("Compilation/song.txt"),
            onCheckpoint = checkpoints::add,
        )

        assertEquals(0, result.extractedEntries)
        assertEquals(1, result.skippedEntries)
        assertTrue(checkpoints.single().entryComplete)
    }

    @Test fun `same size completed entry with wrong CRC is replaced instead of skipped`() = runBlocking {
        val expected = "correct bytes".toByteArray()
        val archive = zip("Compilation/song.txt" to expected)
        val root = temporary.newFolder("crc-output")
        val output = File(root, "Compilation/song.txt")
        output.parentFile.mkdirs()
        output.writeBytes("wrong!! bytes".toByteArray())
        assertEquals(expected.size.toLong(), output.length())

        val result = SafeZipExtractor().extract(
            archive,
            AppSpecificVaultStorage(root),
            completedEntries = setOf("Compilation/song.txt"),
        )

        assertEquals(1, result.extractedEntries)
        assertEquals(0, result.skippedEntries)
        assertArrayEquals(expected, output.readBytes())
    }

    @Test fun `permission loss during publication preserves prior final and retry archive`() = runBlocking {
        val archive = zip("Compilation/song.txt" to "replacement".toByteArray())
        val root = temporary.newFolder("permission-loss-output")
        val prior = File(root, "Compilation/song.txt")
        prior.parentFile.mkdirs()
        prior.writeText("prior final")
        val backing = AppSpecificVaultStorage(root)
        val revokedAtPublication = object : VaultStorage by backing {
            override fun move(source: VaultPath, destination: VaultPath, replaceExisting: Boolean) {
                throw StoragePermissionLostException("fixture permission revoked")
            }
        }

        val failure = runCatching { SafeZipExtractor().extract(archive, revokedAtPublication) }.exceptionOrNull()

        assertTrue(failure is StoragePermissionLostException)
        assertEquals("prior final", prior.readText())
        assertTrue(archive.isFile)
        assertFalse(File(root, "Compilation/.song.txt.vault-part").exists())
    }

    @Test fun `cancelling blocked extraction closes entry source and destination sink`() = runBlocking {
        val payload = ByteArray(16 * 1024).also { Random(42).nextBytes(it) }
        val archive = zip("Compilation/blocked.bin" to payload)
        val source = BlockingInputStream()
        val sinkClosed = CountDownLatch(1)
        val backing = AppSpecificVaultStorage(temporary.newFolder("cancel-output"))
        val storage = object : VaultStorage by backing {
            override fun openSink(path: VaultPath, offset: Long): OutputStream =
                ClosingOutputStream(backing.openSink(path, offset), sinkClosed)
        }
        val extractor = SafeZipExtractor(entrySourceFactory = { _, _ -> source })

        val job = launch(Dispatchers.Default) { extractor.extract(archive, storage) }
        assertTrue(source.readStarted.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue(source.closed.await(5, TimeUnit.SECONDS))
        assertTrue(sinkClosed.await(5, TimeUnit.SECONDS))
        assertFalse(job.isActive)
    }

    @Test fun `cancelling blocked destination write closes both streams`() = runBlocking {
        val payload = ByteArray(16 * 1024).also { Random(84).nextBytes(it) }
        val archive = zip("Compilation/blocked-sink.bin" to payload)
        val sourceClosed = CountDownLatch(1)
        val sink = BlockingOutputStream()
        val backing = AppSpecificVaultStorage(temporary.newFolder("cancel-sink-output"))
        val storage = object : VaultStorage by backing {
            override fun openSink(path: VaultPath, offset: Long): OutputStream {
                sink.delegate = backing.openSink(path, offset)
                return sink
            }
        }
        val extractor = SafeZipExtractor(
            entrySourceFactory = { zip, entry -> ClosingInputStream(zip.getInputStream(entry), sourceClosed) },
        )

        val job = launch(Dispatchers.Default) { extractor.extract(archive, storage) }
        assertTrue(sink.writeStarted.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue(sink.closed.await(5, TimeUnit.SECONDS))
        assertTrue(sourceClosed.await(5, TimeUnit.SECONDS))
        assertFalse(job.isActive)
    }

    @Test fun `zip64 end records are recognized and extracted`() = runBlocking {
        val payload = ByteArray(4096) { (it % 127).toByte() }
        val archive = convertToZip64(zip("Compilation/zip64.bin" to payload))
        val root = temporary.newFolder("zip64-output")
        val extractor = SafeZipExtractor()

        assertTrue(extractor.inspect(archive).zip64)
        val result = extractor.extract(archive, AppSpecificVaultStorage(root))

        assertTrue(result.zip64)
        assertArrayEquals(payload, File(root, "Compilation/zip64.bin").readBytes())
    }

    @Test fun `entry count size and compression ratio limits are enforced`() {
        val twoEntries = zip("one" to byteArrayOf(1), "two" to byteArrayOf(2))
        assertTrue(runCatching { SafeZipExtractor(ZipSafetyLimits(maxEntries = 1)).inspect(twoEntries) }.isFailure)
        val large = zip("large" to ByteArray(2048))
        assertTrue(runCatching {
            SafeZipExtractor(ZipSafetyLimits(maxEntryBytes = 1024, maxTotalBytes = 4096)).inspect(large)
        }.isFailure)
        assertTrue(runCatching {
            SafeZipExtractor(ZipSafetyLimits(maxCompressionRatio = 2.0)).inspect(large)
        }.isFailure)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = temporary.newFile("fixture-${fixtureNumber++}.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun markFirstEntryAsSymlink(source: File): File {
        val bytes = source.readBytes()
        val central = findSignature(bytes, CENTRAL_SIGNATURE)
        bytes[central + 5] = 3 // Unix creator system in version-made-by high byte.
        bytes[central + 40] = 0xff.toByte()
        bytes[central + 41] = 0xa1.toByte() // 0120777 symbolic-link mode.
        return temporary.newFile("fixture-${fixtureNumber++}-symlink.zip").also { it.writeBytes(bytes) }
    }

    private fun convertToZip64(source: File): File {
        val bytes = source.readBytes()
        val eocd = findSignature(bytes, EOCD_SIGNATURE)
        val entries = bytes.uShort(eocd + 10).toLong()
        val centralSize = bytes.uInt(eocd + 12)
        val centralOffset = bytes.uInt(eocd + 16)
        val output = ByteArrayOutputStream(bytes.size + 76)
        output.write(bytes, 0, eocd)
        output.writeUInt(ZIP64_EOCD_SIGNATURE)
        output.writeLong(44)
        output.writeUShort(45)
        output.writeUShort(45)
        output.writeUInt(0)
        output.writeUInt(0)
        output.writeLong(entries)
        output.writeLong(entries)
        output.writeLong(centralSize)
        output.writeLong(centralOffset)
        output.writeUInt(ZIP64_LOCATOR_SIGNATURE)
        output.writeUInt(0)
        output.writeLong(eocd.toLong())
        output.writeUInt(1)
        val legacy = bytes.copyOfRange(eocd, bytes.size)
        legacy[8] = 0xff.toByte(); legacy[9] = 0xff.toByte()
        legacy[10] = 0xff.toByte(); legacy[11] = 0xff.toByte()
        for (index in 12..19) legacy[index] = 0xff.toByte()
        output.write(legacy)
        return temporary.newFile("fixture-${fixtureNumber++}-zip64.zip").also { it.writeBytes(output.toByteArray()) }
    }

    private fun findSignature(bytes: ByteArray, signature: Long): Int {
        for (index in bytes.size - 4 downTo 0) if (bytes.uInt(index) == signature) return index
        error("Signature not found")
    }

    private fun ByteArray.uShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.uInt(offset: Int): Long {
        var result = 0L
        repeat(4) { result = result or ((this[offset + it].toLong() and 0xff) shl (8 * it)) }
        return result
    }

    private fun ByteArrayOutputStream.writeUShort(value: Int) {
        repeat(2) { write(value ushr (8 * it)) }
    }

    private fun ByteArrayOutputStream.writeUInt(value: Long) {
        repeat(4) { write((value ushr (8 * it)).toInt()) }
    }

    private fun ByteArrayOutputStream.writeLong(value: Long) {
        repeat(8) { write((value ushr (8 * it)).toInt()) }
    }

    private class BlockingInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val monitor = Object()
        private var isClosed = false

        override fun read(): Int {
            synchronized(monitor) {
                readStarted.countDown()
                while (!isClosed) monitor.wait()
                return -1
            }
        }

        override fun close() {
            synchronized(monitor) {
                isClosed = true
                monitor.notifyAll()
            }
            closed.countDown()
        }
    }

    private class ClosingOutputStream(
        private val delegate: OutputStream,
        private val closed: CountDownLatch,
    ) : OutputStream() {
        override fun write(value: Int) = delegate.write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = delegate.write(bytes, offset, length)
        override fun flush() = delegate.flush()
        override fun close() {
            delegate.close()
            closed.countDown()
        }
    }

    private class ClosingInputStream(
        private val delegate: InputStream,
        private val closed: CountDownLatch,
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = delegate.read(bytes, offset, length)
        override fun close() {
            delegate.close()
            closed.countDown()
        }
    }

    private class BlockingOutputStream : OutputStream() {
        val writeStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        lateinit var delegate: OutputStream
        private val monitor = Object()
        private var isClosed = false

        override fun write(value: Int) = blockUntilClosed()
        override fun write(bytes: ByteArray, offset: Int, length: Int) = blockUntilClosed()

        private fun blockUntilClosed() {
            synchronized(monitor) {
                writeStarted.countDown()
                while (!isClosed) monitor.wait()
            }
            throw IOException("closed")
        }

        override fun close() {
            synchronized(monitor) {
                isClosed = true
                monitor.notifyAll()
            }
            delegate.close()
            closed.countDown()
        }
    }

    companion object {
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
        private var fixtureNumber = 0
    }
}
