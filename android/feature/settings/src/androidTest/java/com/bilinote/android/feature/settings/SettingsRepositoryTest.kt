package com.bilinote.android.feature.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SettingsRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `saveSettings encrypts token and round trips`() = runBlocking {
        val dataStore = newDataStore()
        val repository = SettingsRepository(dataStore)

        repository.saveSettings("http://localhost:8080", "round-trip-token")

        val settings = repository.observeSettings.first()
        val stored = dataStore.data.first()[SettingsRepository.ACCESS_TOKEN]
        assertEquals("round-trip-token", settings.accessToken)
        assertTrue(stored.orEmpty().startsWith("v1:"))
        assertNotEquals("round-trip-token", stored)
    }

    @Test fun `migrates legacy plaintext token on first read`() = runBlocking {
        val dataStore = newDataStore()
        dataStore.edit {
            it[SettingsRepository.BACKEND_URL] = "http://localhost:8080"
            it[SettingsRepository.ACCESS_TOKEN] = "legacy-token"
        }
        val repository = SettingsRepository(dataStore)

        val settings = repository.observeSettings.first()
        val stored = dataStore.data.first()[SettingsRepository.ACCESS_TOKEN]
        assertEquals("legacy-token", settings.accessToken)
        assertTrue(stored.orEmpty().startsWith("v1:"))
        assertNotEquals("legacy-token", stored)
    }

    @Test fun `reports corrupted v1 token`() = runBlocking {
        val dataStore = newDataStore()
        dataStore.edit { it[SettingsRepository.ACCESS_TOKEN] = "v1:corrupted" }

        val settings = SettingsRepository(dataStore).observeSettings.first()

        assertNull(settings.accessToken)
        assertEquals("Access token 无法解密，请重新输入", settings.tokenError)
    }

    @Test fun `context constructors share one datastore`() = runBlocking {
        val isolatedDirectory = File(context.cacheDir, "settings-context-${UUID.randomUUID()}")
            .also(File::mkdirs)
        val firstRepository = SettingsRepository(IsolatedContext(context, isolatedDirectory))
        val secondRepository = SettingsRepository(IsolatedContext(context, isolatedDirectory))

        firstRepository.clear()
        try {
            firstRepository.saveUrl("http://localhost:8080")

            assertEquals(
                "http://localhost:8080",
                secondRepository.observeSettings.first().backendUrl,
            )
        } finally {
            firstRepository.clear()
            isolatedDirectory.deleteRecursively()
        }
    }

    private fun newDataStore() = PreferenceDataStoreFactory.create {
        File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
    }

    private class IsolatedContext(
        base: Context,
        private val isolatedFilesDir: File,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = isolatedFilesDir
    }
}
