package com.vault999.android.auth

import com.vault999.android.model.Account
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `round trips encrypted bounded session without plaintext credentials`() {
        val file = temporaryFolder.root.toPath().resolve("account.session")
        val store = AtomicEncryptedSessionStore(file, DeterministicAesGcmCipher())
        store.write(session("access-token-secret", "refresh-token-secret"), 42)

        val diskText = String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
        assertFalse(diskText.contains("access-token-secret"))
        assertFalse(diskText.contains("refresh-token-secret"))
        val loaded = store.read() as SessionReadResult.Loaded
        assertEquals("account-1", loaded.session.account.id)
        loaded.session.useAccessToken { it.use { value -> assertEquals("access-token-secret", value) } }
        loaded.session.useRefreshToken { it.use { value -> assertEquals("refresh-token-secret", value) } }
        assertFalse(loaded.toString().contains("access-token-secret"))
    }

    @Test fun `authenticated corruption is reported and clear removes all session files`() {
        val file = temporaryFolder.root.toPath().resolve("account.session")
        val store = AtomicEncryptedSessionStore(file, DeterministicAesGcmCipher())
        store.write(session(), 42)
        val damaged = Files.readAllBytes(file).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        Files.write(file, damaged)

        assertEquals(SessionReadResult.Corrupt, store.read())
        store.clear()
        assertFalse(Files.exists(file))
        assertFalse(Files.exists(file.resolveSibling("account.session.tmp")))
    }

    @Test fun `rotation replaces the complete prior envelope`() {
        val file = temporaryFolder.root.toPath().resolve("account.session")
        val store = AtomicEncryptedSessionStore(file, DeterministicAesGcmCipher())
        store.write(session(access = "first"), 1)
        store.write(session(access = "second"), 2)

        val loaded = store.read() as SessionReadResult.Loaded
        loaded.session.useAccessToken { it.use { value -> assertEquals("second", value) } }
        assertTrue(Files.size(file) > 0)
        assertFalse(Files.exists(file.resolveSibling("account.session.tmp")))
    }

    private fun session(access: String = "access", refresh: String = "refresh") = AccountSession.create(
        account = Account("account-1", "Listener", "listener"),
        accessToken = access,
        refreshToken = refresh,
        accessExpiresAtEpochMs = 10_000,
    )
}

private class DeterministicAesGcmCipher : SessionEnvelopeCipher {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("test-key".toByteArray()), "AES")
    private var counter = 0L

    override fun encrypt(plaintext: ByteArray): EncryptedEnvelope {
        val nonce = ByteBuffer.allocate(12).putInt(0x999).putLong(++counter).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return EncryptedEnvelope(nonce = nonce, ciphertext = cipher.doFinal(plaintext))
    }

    override fun decrypt(envelope: EncryptedEnvelope): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.nonce()))
        return cipher.doFinal(envelope.ciphertext())
    }
}
