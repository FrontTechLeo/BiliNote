package com.bilinote.android.feature.settings

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `connection test uses current draft values`() {
        val tester = RecordingConnectionTester()
        val viewModel = SettingsViewModel(FakeSettingsStore(), tester)
        composeRule.setContent { SettingsScreen(viewModel) }

        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("http://localhost:8080")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("draft-token")
        composeRule.onNodeWithText("测试连接").performClick()
        composeRule.waitForIdle()

        assertEquals("http://localhost:8080", tester.url)
        assertEquals("draft-token", tester.token)
    }

    @Test fun `token has accessible show and hide control`() {
        val viewModel = SettingsViewModel(FakeSettingsStore(), RecordingConnectionTester())
        composeRule.setContent { SettingsScreen(viewModel) }

        composeRule.onNodeWithText("显示").assertExists().performClick()
        composeRule.onNodeWithText("隐藏").assertExists()
    }

    private class RecordingConnectionTester : SettingsConnectionTester {
        var url: String? = null
        var token: String? = null

        override suspend fun test(url: String, token: String?) {
            this.url = url
            this.token = token
        }
    }

    private class FakeSettingsStore : SettingsStore {
        override val observeSettings: StateFlow<SavedSettings> = MutableStateFlow(SavedSettings())
        override suspend fun saveSettings(url: String, token: String?) = Unit
        override suspend fun saveUrl(url: String) = Unit
        override suspend fun saveToken(token: String?) = Unit
    }
}
