package com.vault999.android.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedEnvelope(
    val version: Int = 1,
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    private val nonceBytes = nonce.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()

    fun nonce(): ByteArray = nonceBytes.copyOf()
    fun ciphertext(): ByteArray = ciphertextBytes.copyOf()

    override fun toString(): String =
        "EncryptedEnvelope(version=$version, nonceBytes=${nonceBytes.size}, ciphertextBytes=${ciphertextBytes.size})"
}

interface SessionEnvelopeCipher {
    fun encrypt(plaintext: ByteArray): EncryptedEnvelope
    fun decrypt(envelope: EncryptedEnvelope): ByteArray
}

class KeystoreSessionEnvelope(private val alias: String = "vault999-account-session-v1") : SessionEnvelopeCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedEnvelope {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return EncryptedEnvelope(nonce = cipher.iv, ciphertext = cipher.doFinal(plaintext))
    }

    override fun decrypt(envelope: EncryptedEnvelope): ByteArray {
        require(envelope.version == 1) { "Unsupported session envelope" }
        val nonce = envelope.nonce()
        require(nonce.size == 12) { "Invalid session nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
        return cipher.doFinal(envelope.ciphertext())
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }
}
