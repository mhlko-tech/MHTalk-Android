package com.mhlko.talk.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores session secrets encrypted with a non-exportable Android Keystore key. */
internal class SecureTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("mhtalk.auth.secure", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun put(key: String, value: String) {
        preferences.edit().putString(key, encrypt(value)).apply()
    }

    /** Saves a rotated token pair in one preferences transaction. */
    fun putAll(values: Map<String, String>) {
        val encrypted = values.mapValues { (_, value) -> encrypt(value) }
        val editor = preferences.edit()
        encrypted.forEach { (key, value) -> editor.putString(key, value) }
        check(editor.commit()) { "Could not persist the secure session" }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + cipher.iv.size + encrypted.size)
        packed[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(packed, 1)
        encrypted.copyInto(packed, 1 + cipher.iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun get(key: String): String? = runCatching {
        val packed = Base64.decode(preferences.getString(key, null) ?: return null, Base64.NO_WRAP)
        val ivSize = packed.first().toInt() and 0xff
        require(ivSize in 12..16 && packed.size > ivSize + 1)
        val iv = packed.copyOfRange(1, 1 + ivSize)
        val ciphertext = packed.copyOfRange(1 + ivSize, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }.getOrNull()

    fun remove(key: String) { preferences.edit().remove(key).apply() }
    fun clear() { preferences.edit().clear().apply() }

    private companion object {
        const val KEY_ALIAS = "mhtalk-auth-session-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
