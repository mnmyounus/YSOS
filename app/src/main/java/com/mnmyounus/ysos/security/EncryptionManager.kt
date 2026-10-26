package com.mnmyounus.ysos.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption backed by the Android Keystore.
 * Keys never leave the secure hardware element.
 *
 * File format on disk: [IV (12 bytes)][GCM ciphertext + 16-byte auth tag]
 */
object EncryptionManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "ysos_evidence_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128  // bits
    private const val IV_SIZE = 12           // bytes

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        val spec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        } else {
            // API 21-22: Use software-backed keystore with best available spec
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    /**
     * Encrypts [inputFile] and writes the encrypted result to [outputFile].
     */
    fun encryptFile(inputFile: File, outputFile: File) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // auto-generated random IV

        FileInputStream(inputFile).use { fis ->
            FileOutputStream(outputFile).use { fos ->
                fos.write(iv)
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    cipher.update(buf, 0, n)?.let { fos.write(it) }
                }
                cipher.doFinal()?.let { fos.write(it) }
            }
        }
    }

    /**
     * Decrypts [inputFile] to [outputFile].
     */
    fun decryptFile(inputFile: File, outputFile: File) {
        val key = getOrCreateKey()
        FileInputStream(inputFile).use { fis ->
            val iv = ByteArray(IV_SIZE).also { fis.read(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            }
            FileOutputStream(outputFile).use { fos ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    cipher.update(buf, 0, n)?.let { fos.write(it) }
                }
                cipher.doFinal()?.let { fos.write(it) }
            }
        }
    }

    /**
     * Convenience: encrypts and deletes the raw source file.
     * Returns the encrypted .enc file, or null on failure.
     */
    fun encryptAndDelete(rawFile: File): File? {
        return try {
            val enc = File(rawFile.parent, "${rawFile.name}.enc")
            encryptFile(rawFile, enc)
            rawFile.delete()
            enc
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
