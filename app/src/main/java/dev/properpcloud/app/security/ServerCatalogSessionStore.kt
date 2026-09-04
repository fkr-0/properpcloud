package dev.properpcloud.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.properpcloud.source.server.ServerCatalogSession
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ServerCatalogSessionStore {
    fun read(): ServerCatalogSession?
    fun write(session: ServerCatalogSession)
    fun clear()
}

class EncryptedServerCatalogVault(context: Context) : ServerCatalogSessionStore {
    private val preferences = context.getSharedPreferences("server_catalog_session", Context.MODE_PRIVATE)

    override fun read(): ServerCatalogSession? = runCatching {
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)
        val iv = preferences.getString(KEY_IV, null)
        val token = if (ciphertext == null && iv == null) {
            null
        } else {
            require(ciphertext != null && iv != null) { "incomplete encrypted server credential" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            try {
                plaintext.toString(Charsets.UTF_8)
            } finally {
                plaintext.fill(0)
            }
        }
        ServerCatalogSession(baseUrl, token)
    }.getOrNull()

    override fun write(session: ServerCatalogSession) {
        val token = session.apiToken
        if (token == null) {
            preferences.edit(commit = true) {
                putString(KEY_BASE_URL, session.normalizedBaseUrl)
                remove(KEY_CIPHERTEXT)
                remove(KEY_IV)
            }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = token.toByteArray(Charsets.UTF_8)
        try {
            val ciphertext = cipher.doFinal(bytes)
            preferences.edit(commit = true) {
                putString(KEY_BASE_URL, session.normalizedBaseUrl)
                putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            }
        } finally {
            bytes.fill(0)
        }
    }

    override fun clear() {
        preferences.edit(commit = true) { clear() }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
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
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "properpcloud.server.catalog"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BASE_URL = "base_url"
        const val KEY_CIPHERTEXT = "token_ciphertext"
        const val KEY_IV = "token_iv"
    }
}
