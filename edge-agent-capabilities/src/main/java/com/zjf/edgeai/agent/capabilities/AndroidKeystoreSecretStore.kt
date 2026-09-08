package com.zjf.edgeai.agent.capabilities

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zjf.edgeai.agent.api.SecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreSecretStore(context: Context) : SecretStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "edge-agent-encrypted-secrets",
        Context.MODE_PRIVATE,
    )
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun put(id: String, value: String) {
        validateId(id)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        check(preferences.edit().putString(id, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
            "凭证持久化失败"
        }
    }

    override fun get(id: String): String? {
        validateId(id)
        val encoded = preferences.getString(id, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, payload.copyOfRange(0, IV_SIZE)),
            )
            cipher.doFinal(payload.copyOfRange(IV_SIZE, payload.size)).decodeToString()
        }.getOrNull()
    }

    override fun delete(id: String): Boolean {
        validateId(id)
        if (!preferences.contains(id)) return false
        return preferences.edit().remove(id).commit()
    }

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
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun validateId(id: String) {
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}"))) { "非法凭证 ID" }
    }

    private companion object {
        const val KEY_ALIAS = "edge-agent-sdk-secrets-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
