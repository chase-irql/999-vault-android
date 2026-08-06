package com.vault999.android.account

import com.vault999.android.auth.EncryptedEnvelope
import com.vault999.android.auth.KeystoreSessionEnvelope
import com.vault999.android.auth.OpaqueSecret
import com.vault999.android.auth.PendingAuthorization
import com.vault999.android.auth.PendingAuthorizationStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class EncryptedPendingAuthorizationStore(
    private val file: Path,
    private val cipher: KeystoreSessionEnvelope = KeystoreSessionEnvelope("vault999-pending-auth-v1"),
) : PendingAuthorizationStore {
    private val lock = Any()

    override fun read(): PendingAuthorization? = synchronized(lock) {
        if (!Files.exists(file)) return@synchronized null
        val encoded = Files.readAllBytes(file)
        try {
            require(encoded.size in 40..MAX_FILE_BYTES)
            val input = ByteBuffer.wrap(encoded)
            require(input.int == MAGIC && input.int == VERSION)
            val nonceSize = input.int
            val cipherSize = input.int
            require(nonceSize in 12..32 && cipherSize in 16..MAX_FILE_BYTES && input.remaining() == nonceSize + cipherSize)
            val nonce = ByteArray(nonceSize).also(input::get)
            val ciphertext = ByteArray(cipherSize).also(input::get)
            val plaintext = cipher.decrypt(EncryptedEnvelope(nonce = nonce, ciphertext = ciphertext))
            try { decode(plaintext) } finally { plaintext.fill(0) }
        } catch (failure: Exception) {
            throw IOException("Pending authorization is unreadable", failure)
        } finally { encoded.fill(0) }
    }

    override fun write(pending: PendingAuthorization): Unit = synchronized(lock) {
        Files.createDirectories(file.toAbsolutePath().parent)
        val plaintext = encode(pending)
        val envelope = try { cipher.encrypt(plaintext) } finally { plaintext.fill(0) }
        val nonce = envelope.nonce()
        val ciphertext = envelope.ciphertext()
        val encoded = ByteBuffer.allocate(16 + nonce.size + ciphertext.size)
            .putInt(MAGIC).putInt(VERSION).putInt(nonce.size).putInt(ciphertext.size)
            .put(nonce).put(ciphertext).array()
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        try {
            Files.write(temporary, encoded)
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            encoded.fill(0)
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    override fun clear() = synchronized(lock) {
        Files.deleteIfExists(file)
        Files.deleteIfExists(file.resolveSibling("${file.fileName}.tmp"))
        Unit
    }

    private fun encode(pending: PendingAuthorization): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUTF(pending.state)
            output.writeUTF(pending.challenge)
            output.writeLong(pending.createdAtEpochMs)
            pending.verifier.use { output.writeUTF(it) }
        }
    }.toByteArray().also { require(it.size <= MAX_PLAINTEXT_BYTES) }

    private fun decode(value: ByteArray): PendingAuthorization = DataInputStream(ByteArrayInputStream(value)).use { input ->
        val state = input.readUTF()
        val challenge = input.readUTF()
        val created = input.readLong()
        val verifier = input.readUTF()
        require(input.available() == 0 && state.length in 20..256 && challenge.length in 20..256 && verifier.length in 43..256)
        PendingAuthorization(state, OpaqueSecret.from(verifier), challenge, created)
    }

    companion object {
        private const val MAGIC = 0x39394155
        private const val VERSION = 1
        private const val MAX_PLAINTEXT_BYTES = 4 * 1024
        private const val MAX_FILE_BYTES = 16 * 1024
    }
}
