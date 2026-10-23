package com.aidenir.riff.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.aidenir.riff.ffi.SecretStore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credentials for the core, encrypted with an AES-GCM key that never leaves
 * the Android Keystore. Each account is one file in app storage holding the
 * IV and the ciphertext; both survive app updates.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val dir = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun file(account: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }

    @Synchronized
    override fun get(account: String): ByteArray? {
        val file = file(account)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val ivLength = bytes[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, ivLength))
            cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable credentials, forgetting them", e)
            file.delete()
            null
        }
    }

    @Synchronized
    override fun set(account: String, secret: ByteArray): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(secret)
        val tmp = File(dir, "${file(account).name}.tmp")
        tmp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + sealed)
        tmp.renameTo(file(account))
    } catch (e: Exception) {
        Log.e(TAG, "Could not store credentials", e)
        false
    }

    @Synchronized
    override fun delete(account: String) {
        file(account).delete()
    }

    private companion object {
        const val TAG = "RiffSecrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "riff-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
