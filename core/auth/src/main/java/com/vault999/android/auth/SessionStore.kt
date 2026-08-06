package com.vault999.android.auth

import com.vault999.android.model.Account
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

sealed interface SessionReadResult {
    data object Missing : SessionReadResult
    data class Loaded(val session: AccountSession) : SessionReadResult {
        override fun toString(): String = "Loaded(session=$session)"
    }
    data object Corrupt : SessionReadResult
}

interface SessionStore {
    @Throws(IOException::class)
    fun read(): SessionReadResult

    @Throws(IOException::class)
    fun write(session: AccountSession, writtenAtEpochMs: Long)

    @Throws(IOException::class)
    fun clear()
}

/** Versioned encrypted session file with bounded parsing and atomic same-directory rotation. */
class AtomicEncryptedSessionStore(
    private val file: Path,
    private val cipher: SessionEnvelopeCipher,
) : SessionStore {
    private val lock = Any()
    private val tombstone: Path = file.resolveSibling("${file.fileName}.signed-out")

    override fun read(): SessionReadResult = synchronized(lock) {
        // The tombstone is authoritative even if an earlier session envelope remains after a
        // partial clear. This prevents stale credentials from returning after process death.
        if (Files.exists(tombstone)) return@synchronized SessionReadResult.Missing
        if (!Files.exists(file)) return@synchronized SessionReadResult.Missing
        val encoded = try {
            val size = Files.size(file)
            if (size !in MIN_FILE_BYTES..MAX_FILE_BYTES) return@synchronized SessionReadResult.Corrupt
            Files.readAllBytes(file)
        } catch (failure: IOException) {
            throw failure
        }

        try {
            val envelope = decodeEnvelope(encoded)
            val plaintext = cipher.decrypt(envelope)
            try {
                SessionCodec.decode(plaintext).let(SessionReadResult::Loaded)
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            SessionReadResult.Corrupt
        } finally {
            encoded.fill(0)
        }
    }

    override fun write(session: AccountSession, writtenAtEpochMs: Long): Unit = synchronized(lock) {
        require(writtenAtEpochMs >= 0) { "Invalid envelope timestamp" }
        Files.createDirectories(file.toAbsolutePath().parent)
        val plaintext = SessionCodec.encode(session)
        val encoded = try {
            encodeEnvelope(cipher.encrypt(plaintext), writtenAtEpochMs)
        } finally {
            plaintext.fill(0)
        }
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(encoded)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Remove the signed-out marker only after a complete new session is durable.
            Files.deleteIfExists(tombstone)
            Files.deleteIfExists(tombstone.resolveSibling("${tombstone.fileName}.tmp"))
        } finally {
            encoded.fill(0)
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    override fun clear(): Unit = synchronized(lock) {
        Files.createDirectories(file.toAbsolutePath().parent)
        val markerTemporary = tombstone.resolveSibling("${tombstone.fileName}.tmp")
        try {
            FileChannel.open(
                markerTemporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(TOMBSTONE_BYTES)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(markerTemporary, tombstone, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(markerTemporary)
        }
        // Deletion is cleanup only: the already-durable marker makes any old envelope unreadable.
        Files.deleteIfExists(file)
        Files.deleteIfExists(file.resolveSibling("${file.fileName}.tmp"))
        Unit
    }

    private fun encodeEnvelope(envelope: EncryptedEnvelope, writtenAtEpochMs: Long): ByteArray {
        val nonce = envelope.nonce()
        val ciphertext = envelope.ciphertext()
        require(envelope.version == ENVELOPE_VERSION) { "Unsupported encrypted envelope" }
        require(nonce.size in 12..32) { "Invalid nonce length" }
        require(ciphertext.size in 16..MAX_CIPHERTEXT_BYTES) { "Invalid ciphertext length" }
        return ByteBuffer.allocate(HEADER_BYTES + nonce.size + ciphertext.size)
            .putInt(FILE_MAGIC)
            .putInt(FILE_VERSION)
            .putInt(envelope.version)
            .putLong(writtenAtEpochMs)
            .putInt(nonce.size)
            .putInt(ciphertext.size)
            .put(nonce)
            .put(ciphertext)
            .array()
    }

    private fun decodeEnvelope(encoded: ByteArray): EncryptedEnvelope {
        if (encoded.size < HEADER_BYTES) throw SessionCorruptionException()
        val input = ByteBuffer.wrap(encoded)
        if (input.int != FILE_MAGIC || input.int != FILE_VERSION) throw SessionCorruptionException()
        val envelopeVersion = input.int
        if (envelopeVersion != ENVELOPE_VERSION) throw SessionCorruptionException()
        if (input.long < 0) throw SessionCorruptionException()
        val nonceLength = input.int
        val ciphertextLength = input.int
        if (nonceLength !in 12..32 || ciphertextLength !in 16..MAX_CIPHERTEXT_BYTES) throw SessionCorruptionException()
        if (input.remaining() != nonceLength + ciphertextLength) throw SessionCorruptionException()
        val nonce = ByteArray(nonceLength).also(input::get)
        val ciphertext = ByteArray(ciphertextLength).also(input::get)
        return EncryptedEnvelope(envelopeVersion, nonce, ciphertext)
    }

    private class SessionCorruptionException : Exception()

    private companion object {
        const val FILE_MAGIC = 0x39393956
        const val FILE_VERSION = 1
        const val ENVELOPE_VERSION = 1
        const val HEADER_BYTES = 28
        const val MIN_FILE_BYTES = HEADER_BYTES + 12 + 16
        const val MAX_CIPHERTEXT_BYTES = 512 * 1024
        const val MAX_FILE_BYTES = HEADER_BYTES + 32 + MAX_CIPHERTEXT_BYTES
        val TOMBSTONE_BYTES = byteArrayOf(0x39, 0x39, 0x39, 0x00)
    }
}

private object SessionCodec {
    private const val SESSION_VERSION = 1
    private const val MAX_FIELD_BYTES = 128 * 1024
    private const val MAX_SESSION_BYTES = 512 * 1024

    fun encode(session: AccountSession): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(SESSION_VERSION)
            output.writeString(session.account.id)
            output.writeString(session.account.displayName)
            output.writeNullableString(session.account.discordUsername)
            output.writeNullableString(session.account.avatarUrl)
            output.writeBoolean(session.account.cached)
            output.writeLong(session.accessExpiresAtEpochMs)
            session.useAccessToken { secret -> secret.use { output.writeString(it) } }
            session.useRefreshToken { secret -> secret.use { output.writeString(it) } }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_SESSION_BYTES) { "Session exceeds safety bound" } }
    }

    fun decode(bytes: ByteArray): AccountSession {
        if (bytes.size !in 1..MAX_SESSION_BYTES) throw IllegalArgumentException("Invalid session size")
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != SESSION_VERSION) throw IllegalArgumentException("Unsupported session version")
            val account = Account(
                id = input.readString(),
                displayName = input.readString(),
                discordUsername = input.readNullableString(),
                avatarUrl = input.readNullableString(),
                cached = input.readBoolean(),
            )
            val expiry = input.readLong()
            val access = input.readString()
            val refresh = input.readString()
            if (input.available() != 0) throw IllegalArgumentException("Trailing session data")
            return AccountSession.create(account, access, refresh, expiry)
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_FIELD_BYTES) { "Session field exceeds safety bound" }
        writeInt(encoded.size)
        write(encoded)
        encoded.fill(0)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readString(): String {
        val size = try {
            readInt()
        } catch (failure: EOFException) {
            throw IllegalArgumentException("Truncated session", failure)
        }
        if (size !in 0..MAX_FIELD_BYTES || size > available()) throw IllegalArgumentException("Invalid session field")
        val encoded = ByteArray(size)
        readFully(encoded)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString()
        } finally {
            encoded.fill(0)
        }
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null
}
