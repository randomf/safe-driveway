package io.github.randomf.safedriveway

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the phone stores, in one file in the app's private storage, encrypted with AES-GCM under a
 * key that the Android Keystore holds and never lets out (ADR 0011). The file is replaced whole, so
 * a crash mid-save leaves the old one.
 */
class KeystoreStorage(context: Context) : SecureStorage {
    private val file = AtomicFile(File(context.filesDir, "phone.bin"))

    override fun load(): ByteArray? = try {
        val bytes = file.readFully()
        cipher(Cipher.DECRYPT_MODE, bytes.copyOf(IV_BYTES)).doFinal(
            bytes,
            IV_BYTES,
            bytes.size - IV_BYTES
        )
    } catch (_: IOException) {
        null
    } catch (_: GeneralSecurityException) {
        // The key is gone, as after the app's data was restored elsewhere: start afresh.
        null
    }

    override fun save(bytes: ByteArray) {
        val cipher = cipher(Cipher.ENCRYPT_MODE, null)
        val encrypted = cipher.iv + cipher.doFinal(bytes)
        val out = file.startWrite()
        try {
            out.write(encrypted)
            file.finishWrite(out)
        } catch (_: IOException) {
            // The old file stays; the next change saves again.
            file.failWrite(out)
        }
    }

    private fun cipher(mode: Int, iv: ByteArray?): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            if (iv == null) init(mode, key()) else init(mode, key(), GCMParameterSpec(TAG_BITS, iv))
        }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "phone-storage"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
