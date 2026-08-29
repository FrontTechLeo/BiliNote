package com.bilinote.android.feature.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedSettings(
    val backendUrl: String = "",
    val accessToken: String? = null,
    val tokenError: String? = null,
)

interface SettingsStore {
    val observeSettings: Flow<SavedSettings>
    suspend fun saveSettings(url: String, token: String?)
    suspend fun saveUrl(url: String)
    suspend fun saveToken(token: String?)
}

class SettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {
    constructor(context: Context) : this(
        dataStoreFor(context.applicationContext),
    )

    override val observeSettings: Flow<SavedSettings> = flow {
        val migration = migrateLegacyToken()
        emitAll(dataStore.data.map { preferences -> mapSettings(preferences, migration) })
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    override suspend fun saveSettings(url: String, token: String?) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            dataStore.edit {
                it[BACKEND_URL] = url
                if (token.isNullOrBlank()) it.remove(ACCESS_TOKEN)
                else it[ACCESS_TOKEN] = TokenCipher.encrypt(token.trim())
            }
        }
    }

    override suspend fun saveUrl(url: String) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            dataStore.edit { it[BACKEND_URL] = url.trim() }
        }
    }

    override suspend fun saveToken(token: String?) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            dataStore.edit {
                if (token.isNullOrBlank()) it.remove(ACCESS_TOKEN)
                else it[ACCESS_TOKEN] = TokenCipher.encrypt(token.trim())
            }
        }
    }

    suspend fun clear() {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            dataStore.edit { it.clear() }
        }
    }

    private suspend fun migrateLegacyToken(): MigrationResult = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val preferences = dataStore.data.first()
        val rawToken = preferences[ACCESS_TOKEN]
        if (rawToken == null || rawToken.startsWith(TokenCipher.PREFIX)) {
            return@withContext MigrationResult.None
        }

        return@withContext try {
            val encrypted = TokenCipher.encrypt(rawToken)
            dataStore.edit { it[ACCESS_TOKEN] = encrypted }
            MigrationResult.Migrated
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            MigrationResult.Failed("Access token 无法安全迁移，请重新输入")
        }
    }

    private fun mapSettings(preferences: Preferences, migration: MigrationResult): SavedSettings {
        val backendUrl = preferences[BACKEND_URL].orEmpty().ifBlank { DEFAULT_BACKEND_URL }
        val rawToken = preferences[ACCESS_TOKEN] ?: return SavedSettings(backendUrl = backendUrl)
        if (!rawToken.startsWith(TokenCipher.PREFIX)) {
            return SavedSettings(backendUrl = backendUrl, tokenError = (migration as? MigrationResult.Failed)?.message)
        }
        return try {
            SavedSettings(backendUrl = backendUrl, accessToken = TokenCipher.decrypt(rawToken))
        } catch (_: Exception) {
            SavedSettings(backendUrl = backendUrl, tokenError = "Access token 无法解密，请重新输入")
        }
    }

    private sealed interface MigrationResult {
        data object None : MigrationResult
        data object Migrated : MigrationResult
        data class Failed(val message: String) : MigrationResult
    }

    internal companion object {
        const val DATASTORE_FILE = "settings.preferences_pb"
        const val DEFAULT_BACKEND_URL = "http://172.21.16.115:8483"
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")

        @Volatile
        private var contextDataStore: DataStore<Preferences>? = null

        private fun dataStoreFor(context: Context): DataStore<Preferences> =
            contextDataStore ?: synchronized(this) {
                contextDataStore ?: PreferenceDataStoreFactory.create {
                    context.preferencesDataStoreFile(DATASTORE_FILE)
                }.also { contextDataStore = it }
            }
    }
}

private object TokenCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "bilinote.settings.access-token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val keyLock = Any()
    const val PREFIX = "v1:"

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(4 + cipher.iv.size + ciphertext.size)
        payload.putInt(cipher.iv.size).put(cipher.iv).put(ciphertext)
        return PREFIX + Base64.encodeToString(payload.array(), Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val payload = ByteBuffer.wrap(Base64.decode(encoded.removePrefix(PREFIX), Base64.NO_WRAP))
        val iv = ByteArray(payload.int).also(payload::get)
        val ciphertext = ByteArray(payload.remaining()).also(payload::get)
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        synchronized(keyLock) {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
        }
    }
}
