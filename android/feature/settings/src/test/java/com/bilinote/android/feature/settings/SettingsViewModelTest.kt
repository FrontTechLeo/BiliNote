package com.bilinote.android.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `first install uses the approved backend URL`() {
        assertEquals("http://172.21.16.115:8483/", SettingsRepository.DEFAULT_BACKEND_URL)
    }

    @Test fun `rejects empty url without testing connection`() = runTest {
        val tester = FakeConnectionTester()
        val viewModel = SettingsViewModel(FakeSettingsStore(), tester)

        viewModel.testConnection("  ", "")
        advanceUntilIdle()

        assertEquals("请输入服务地址", viewModel.uiState.value.errorMessage)
        assertEquals(0, tester.calls)
    }

    @Test fun `uses draft values for successful connection`() = runTest {
        val tester = FakeConnectionTester()
        val viewModel = SettingsViewModel(FakeSettingsStore(), tester)

        viewModel.testConnection("http://localhost:8080/", "token")
        advanceUntilIdle()

        assertEquals("http://localhost:8080/", tester.url)
        assertEquals("token", tester.token)
        assertEquals("连接成功", viewModel.uiState.value.connectionMessage)
    }

    @Test fun `reports connection failure`() = runTest {
        val tester = FakeConnectionTester().also { it.failure = IllegalStateException("offline") }
        val viewModel = SettingsViewModel(FakeSettingsStore(), tester)

        viewModel.testConnection("http://localhost:8080", "")
        advanceUntilIdle()

        assertEquals("offline", viewModel.uiState.value.errorMessage)
    }

    @Test fun `invalid url is not saved`() = runTest {
        val store = FakeSettingsStore()
        val viewModel = SettingsViewModel(store)

        viewModel.save("ftp://example.com", "token")
        advanceUntilIdle()

        assertEquals(null, store.savedUrl)
        assertEquals("Backend URL must use http or https", viewModel.uiState.value.errorMessage)
    }

    @Test fun `older connection result cannot overwrite newer request`() = runTest {
        val firstFinished = CompletableDeferred<Unit>()
        val tester = SequencedConnectionTester(firstFinished)
        val viewModel = SettingsViewModel(FakeSettingsStore(), tester)

        viewModel.testConnection("http://old", "")
        advanceUntilIdle()
        viewModel.testConnection("http://new", "")
        advanceUntilIdle()
        firstFinished.complete(Unit)
        advanceUntilIdle()

        assertEquals("连接成功", viewModel.uiState.value.connectionMessage)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test fun `empty url after cancelling a connection clears testing state`() = runTest {
        val tester = BlockingConnectionTester()
        val viewModel = SettingsViewModel(FakeSettingsStore(), tester)

        viewModel.testConnection("http://old", "")
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.isTestingConnection)

        viewModel.testConnection("  ", "")
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.isTestingConnection)
        tester.release.complete(Unit)
    }

    private class FakeConnectionTester : SettingsConnectionTester {
        var calls = 0
        var url: String? = null
        var token: String? = null
        var failure: Throwable? = null

        override suspend fun test(url: String, token: String?) {
            calls++
            this.url = url
            this.token = token
            failure?.let { throw it }
        }
    }

    private class SequencedConnectionTester(private val firstFinished: CompletableDeferred<Unit>) : SettingsConnectionTester {
        private var calls = 0

        override suspend fun test(url: String, token: String?) {
            calls++
            if (calls == 1) {
                withContext(NonCancellable) {
                    firstFinished.await()
                }
                throw IllegalStateException("old result")
            }
        }
    }

    private class BlockingConnectionTester : SettingsConnectionTester {
        val release = CompletableDeferred<Unit>()

        override suspend fun test(url: String, token: String?) {
            release.await()
        }
    }

    private class FakeSettingsStore : SettingsStore {
        override val observeSettings = MutableStateFlow(SavedSettings())
        var savedUrl: String? = null
        override suspend fun saveUrl(url: String) = Unit
        override suspend fun saveToken(token: String?) = Unit
        override suspend fun saveSettings(url: String, token: String?) {
            savedUrl = url
        }
    }
}
