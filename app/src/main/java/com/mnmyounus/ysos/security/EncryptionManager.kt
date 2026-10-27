package com.mnmyounus.ysos.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption backed by the Android Keystore.
 * Keys never leave the secure hardware element.
 *
 * NEW file format (v2, streamed in chunks so big videos never need to fit in RAM):
 *   "YSOS2" magic (5 bytes)
 *   repeated: [cipherLen: int32][IV: 12 bytes][ciphertext + 16-byte tag]
 *   Each chunk is authenticated with AAD = chunkIndex + "isLastChunk" flag, so
 *   reordering or truncating a recording is detected on decrypt.
 *
 * LEGACY format (v1, still readable): [IV (12 bytes)][GCM ciphertext + tag]
 */
object EncryptionManager {

    private const val TAG = "EncryptionManager"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "ysos_evidence_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128  // bits
    private const val GCM_TAG_BYTES = 16
    private const val IV_SIZE = 12           // bytes
    private const val CHUNK_SIZE = 2 * 1024 * 1024  // 2 MiB of plaintext per chunk
    private const val MIN_VALID_SIZE = 1024L // anything smaller is an empty/failed recording

    private val MAGIC = byteArrayOf('Y'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte())

    // Single background worker so encryption never blocks the main thread (avoids ANR crashes).
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pendingJobs = AtomicInteger(0)

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

    // ---------------------------------------------------------------------------------------------
    // Encrypt
    // ---------------------------------------------------------------------------------------------

    /**
     * Encrypts [inputFile] and writes the encrypted result (v2 format) to [outputFile].
     */
    fun encryptFile(inputFile: File, outputFile: File) {
        val key = getOrCreateKey()
        FileInputStream(inputFile).use { fis ->
            DataOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { out ->
                out.write(MAGIC)
                var current = readChunk(fis)
                var index = 0L
                while (true) {
                    // A chunk is the last one when it is short, or when nothing follows it.
                    val next = if (current.size == CHUNK_SIZE) readChunk(fis) else ByteArray(0)
                    val isLast = next.isEmpty()

                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                    cipher.updateAAD(buildAad(index, isLast))
                    val iv = cipher.iv
                    val encrypted = cipher.doFinal(current)

                    out.writeInt(encrypted.size)
                    out.write(iv)
                    out.write(encrypted)

                    if (isLast) break
                    current = next
                    index++
                }
            }
        }
    }

    /** Reads up to CHUNK_SIZE bytes; the result is shorter only at end of file. */
    private fun readChunk(input: InputStream): ByteArray {
        val buf = ByteArray(CHUNK_SIZE)
        var filled = 0
        while (filled < CHUNK_SIZE) {
            val n = input.read(buf, filled, CHUNK_SIZE - filled)
            if (n < 0) break
            filled += n
        }
        return if (filled == CHUNK_SIZE) buf else buf.copyOf(filled)
    }

    private fun buildAad(index: Long, isLast: Boolean): ByteArray {
        return ByteBuffer.allocate(9)
            .putLong(index)
            .put(if (isLast) 1.toByte() else 0.toByte())
            .array()
    }

    // ---------------------------------------------------------------------------------------------
    // Decrypt
    // ---------------------------------------------------------------------------------------------

    /**
     * Decrypts [inputFile] to [outputFile].
     */
    fun decryptFile(inputFile: File, outputFile: File) {
        FileOutputStream(outputFile).use { fos ->
            BufferedOutputStream(fos).use { out ->
                decryptToStream(inputFile, out)
            }
        }
    }

    /**
     * Decrypts [inputFile] and writes the plaintext to [output] (does not close it).
     * Works for both the new chunked format and old single-block files.
     */
    fun decryptToStream(inputFile: File, output: OutputStream) {
        val key = getOrCreateKey()
        BufferedInputStream(FileInputStream(inputFile)).use { input ->
            input.mark(MAGIC.size)
            val head = ByteArray(MAGIC.size)
            val got = readFully(input, head)
            if (got == MAGIC.size && head.contentEquals(MAGIC)) {
                decryptChunked(input, key, output)
            } else {
                input.reset()
                decryptLegacy(input, key, output)
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Returns the next chunk length, or -1 at a clean end of file. */
    private fun readLengthOrEof(input: DataInputStream): Long {
        val b1 = input.read()
        if (b1 < 0) return -1L
        val b2 = input.read()
        val b3 = input.read()
        val b4 = input.read()
        if (b2 < 0 || b3 < 0 || b4 < 0) throw EOFException("Truncated evidence file")
        val value = (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
        return value.toLong() and 0xFFFFFFFFL
    }

    private fun decryptChunked(input: InputStream, key: SecretKey, output: OutputStream) {
        val din = DataInputStream(input)
        var index = 0L
        var length = readLengthOrEof(din)
        while (length >= 0L) {
            if (length < GCM_TAG_BYTES.toLong() || length > (CHUNK_SIZE + GCM_TAG_BYTES).toLong()) {
                throw IllegalStateException("Corrupt evidence file (bad chunk size)")
            }
            val iv = ByteArray(IV_SIZE)
            din.readFully(iv)
            val encrypted = ByteArray(length.toInt())
            din.readFully(encrypted)

            val nextLength = readLengthOrEof(din)
            val isLast = nextLength < 0L

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher.updateAAD(buildAad(index, isLast))
            output.write(cipher.doFinal(encrypted))

            index++
            length = nextLength
        }
    }

    private fun decryptLegacy(input: InputStream, key: SecretKey, output: OutputStream) {
        val iv = ByteArray(IV_SIZE)
        if (readFully(input, iv) != IV_SIZE) throw EOFException("Corrupt evidence file")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val buf = ByteArray(8192)
        var n = input.read(buf)
        while (n != -1) {
            val part = cipher.update(buf, 0, n)
            if (part != null) output.write(part)
            n = input.read(buf)
        }
        val tail = cipher.doFinal()
        if (tail != null) output.write(tail)
    }

    // ---------------------------------------------------------------------------------------------
    // Convenience
    // ---------------------------------------------------------------------------------------------

    /**
     * Encrypts and deletes the raw source file.
     * Returns the encrypted .enc file, or null if there was nothing worth keeping / on failure.
     * If encryption fails the raw file is kept so evidence is never lost.
     */
    fun encryptAndDelete(rawFile: File): File? {
        if (!rawFile.exists()) return null
        if (rawFile.length() < MIN_VALID_SIZE) {
            // Empty/failed recording (e.g. a camera that never started) - nothing to keep.
            rawFile.delete()
            return null
        }
        val enc = File(rawFile.parent, "${rawFile.name}.enc")
        return try {
            encryptFile(rawFile, enc)
            rawFile.delete()
            enc
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed for ${rawFile.name}", e)
            if (enc.exists()) enc.delete()
            null
        }
    }

    /** Same as [encryptAndDelete] but on a background thread. Safe to call from the main thread. */
    fun encryptAndDeleteAsync(rawFile: File) {
        pendingJobs.incrementAndGet()
        try {
            executor.execute {
                try {
                    encryptAndDelete(rawFile)
                } finally {
                    pendingJobs.decrementAndGet()
                }
            }
        } catch (e: Exception) {
            pendingJobs.decrementAndGet()
            Log.e(TAG, "Could not queue encryption", e)
        }
    }

    /** True while recordings are still being encrypted in the background. */
    fun hasPendingJobs(): Boolean = pendingJobs.get() > 0
}
