package com.bilinote.android.a11y

import androidx.compose.material3.windowsizeclass.WindowSizeClass.Companion.calculateFromSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.bilinote.android.core.designsystem.BiliNoteTheme
import com.bilinote.android.feature.settings.SettingsRepository
import com.bilinote.android.navigation.AppNavHost
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class AccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun clearSavedSettings() = runBlocking {
        SettingsRepository(ApplicationProvider.getApplicationContext()).clear()
    }

    @Test
    fun unconfiguredServiceHasAccessibleState() {
        setContent()

        composeRule.onNodeWithTag("service-not-configured")
            .assertExists()
            .assertContentDescriptionContains("请先配置服务地址")
            .assertContentDescriptionContains("服务")
        composeRule.onNodeWithText("请先配置服务地址").assertExists()
        composeRule.onNodeWithText("前往设置")
            .assertExists()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun serviceActionRemainsReadableAtLargeFontScale() {
        val widthSizeClass = calculateFromSize(DpSize(390.dp, 844.dp)).widthSizeClass
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                BiliNoteTheme {
                    AppNavHost(windowWidthSizeClass = widthSizeClass)
                }
            }
        }

        composeRule.onNodeWithTag("service-not-configured")
            .assertExists()
            .assertContentDescriptionContains("请先配置服务地址")
            .assertContentDescriptionContains("服务")
        composeRule.onNodeWithText("请先配置服务地址").assertExists()
        composeRule.onNodeWithText("前往设置")
            .assertExists()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun primaryNavigationHasAccessibleSemantics() {
        setContent()

        assertNavigationDestination("navigation-rail:Home")
        assertNavigationDestination("navigation-rail:Batch")
        assertNavigationDestination("navigation-rail:Library")
        assertNavigationDestination("navigation-rail:Settings")
    }

    @Test
    fun compactNavigationHasAccessibleSemantics() {
        val widthSizeClass = calculateFromSize(DpSize(390.dp, 844.dp)).widthSizeClass
        composeRule.setContent {
            BiliNoteTheme {
                AppNavHost(windowWidthSizeClass = widthSizeClass)
            }
        }

        assertNavigationDestination("navigation-bottom:Home")
        assertNavigationDestination("navigation-bottom:Batch")
        assertNavigationDestination("navigation-bottom:Library")
        assertNavigationDestination("navigation-bottom:Settings")
    }

    private fun assertNavigationDestination(contentDescription: String) {
        composeRule.onNodeWithContentDescription(contentDescription)
            .assertExists()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    private fun setContent() {
        val widthSizeClass = calculateFromSize(DpSize(800.dp, 800.dp)).widthSizeClass
        composeRule.setContent {
            BiliNoteTheme {
                AppNavHost(windowWidthSizeClass = widthSizeClass)
            }
        }
    }
}
