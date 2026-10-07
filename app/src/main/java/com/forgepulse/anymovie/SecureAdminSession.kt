package com.forgepulse.anymovie

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores admin session only as AES-GCM ciphertext encrypted by Android Keystore. */
class SecureAdminSession(context: Context) {
    private val prefs = context.getSharedPreferences("admin_session_secure_v1", Context.MODE_PRIVATE)
    private val oldPrefs = context.getSharedPreferences("admin_session", Context.MODE_PRIVATE)
    private val keyAlias = "anymovie_admin_session_aes_v1"

    init {
        // Legacy app stored bearer cookies in plaintext. Do not silently keep that insecure copy.
        oldPrefs.edit().remove("cookie").apply()
    }

    fun read(): String? = runCatching {
        val iv = Base64.decode(prefs.getString("iv", null) ?: return@runCatching null, Base64.NO_WRAP)
        val cipherText = Base64.decode(prefs.getString("payload", null) ?: return@runCatching null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()

    fun write(value: String) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            val encoded = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            prefs.edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("payload", encoded)
                .apply()
        }.onFailure { clear() }
    }

    fun clear() { prefs.edit().clear().apply() }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val keygen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keygen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keygen.generateKey()
    }
}
