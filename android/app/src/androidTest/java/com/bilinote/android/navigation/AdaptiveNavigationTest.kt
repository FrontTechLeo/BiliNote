package com.bilinote.android.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.windowsizeclass.WindowSizeClass.Companion.calculateFromSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.bilinote.android.core.designsystem.BiliNoteTheme
import com.bilinote.android.core.designsystem.AdaptiveScaffold
import com.bilinote.android.core.designsystem.BiliNoteNavigationDestination
import com.bilinote.android.feature.batch.BatchAppendRequest
import com.bilinote.android.feature.batch.BatchAppendResult
import com.bilinote.android.feature.batch.BatchConfig
import com.bilinote.android.feature.batch.BatchCreateRequest
import com.bilinote.android.feature.batch.BatchDeleteResult
import com.bilinote.android.feature.batch.BatchEvent
import com.bilinote.android.feature.batch.BatchExport
import com.bilinote.android.feature.batch.BatchItem
import com.bilinote.android.feature.batch.BatchPreview
import com.bilinote.android.feature.batch.BatchRepository
import com.bilinote.android.feature.batch.BatchTask
import com.bilinote.android.feature.batch.BatchTaskPage
import com.bilinote.android.feature.batch.BatchUpdateRequest
import com.bilinote.android.feature.batch.BatchViewModel
import okhttp3.MultipartBody
import androidx.test.core.app.ApplicationProvider
import com.bilinote.android.feature.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class AdaptiveNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun clearSavedSettings() = runBlocking {
        SettingsRepository(ApplicationProvider.getApplicationContext()).clear()
    }

    @Test
    fun compactWidthUsesBottomNavigation() {
        setContentFor(390.dp, 800.dp)

        composeRule.onNodeWithContentDescription("navigation-bottom:Home").assertExists()
        composeRule.onAllNodesWithContentDescription("navigation-rail:Home").assertCountEquals(0)
    }

    @Test
    fun compactNavigationUsesIconInsteadOfUnicodeGlyphs() {
        setContentFor(390.dp, 800.dp)

        composeRule.onAllNodesWithText("⌂", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("▦", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("▤", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("⚙", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun compactNavigationUsesChineseLabels() {
        setContentFor(390.dp, 800.dp)

        composeRule.onNodeWithText("首页").assertExists()
        composeRule.onNodeWithText("合集").assertExists()
        composeRule.onNodeWithText("资料库").assertExists()
        composeRule.onAllNodesWithText("设置").assertCountEquals(0)
    }

    @Test
    fun mediumWidthUsesNavigationRail() {
        setContentFor(800.dp, 800.dp)

        composeRule.onNodeWithContentDescription("navigation-rail:Home").assertExists()
        composeRule.onAllNodesWithContentDescription("navigation-bottom:Home").assertCountEquals(0)
    }

    @Test
    fun navigationRailTabCentersIconAndLabel() {
        composeRule.setContent {
            BiliNoteTheme {
                Box(Modifier.size(800.dp, 800.dp)) {
                    AdaptiveScaffold(
                        windowWidthSizeClass = calculateFromSize(DpSize(800.dp, 800.dp)).widthSizeClass,
                        destinations = listOf(
                            BiliNoteNavigationDestination(
                                id = "home",
                                label = "Home",
                                selected = true,
                                icon = { androidx.compose.material3.Text("ICON") },
                                onClick = {},
                                contentDescription = "navigation-rail:Home",
                            ),
                        ),
                    ) { }
                }
            }
        }

        val tabBounds = composeRule
            .onNodeWithContentDescription("navigation-rail:Home")
            .fetchSemanticsNode()
            .boundsInRoot
        val iconBounds = composeRule
            .onNodeWithText("ICON", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val labelBounds = composeRule
            .onNodeWithText("Home", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        assertEquals(tabBounds.center.x, iconBounds.center.x, 0.5f)
        assertEquals(tabBounds.center.x, labelBounds.center.x, 0.5f)
    }

    @Test
    fun expandedWidthShowsRailAndUnconfiguredServiceState() {
        setContentFor(1280.dp, 800.dp)

        composeRule.onNodeWithContentDescription("navigation-rail:Home").assertExists()
        composeRule.onAllNodesWithContentDescription("navigation-bottom:Home").assertCountEquals(0)
        composeRule.onNodeWithTag("service-not-configured").assertExists()
    }

    @Test
    fun expandedBatchScreenShowsAllStaticPanes() {
        val viewModel = BatchViewModel(StaticBatchRepository(), autoRefresh = false)
        composeRule.setContent {
            BiliNoteTheme {
                Box(Modifier.size(1280.dp, 800.dp)) {
                    com.bilinote.android.feature.batch.BatchScreen(
                        viewModel = viewModel,
                        windowWidthSizeClass = calculateFromSize(DpSize(1280.dp, 800.dp)).widthSizeClass,
                        onOpenDetail = {},
                        onOpenReader = {},
                        onExport = {},
                    )
                }
            }
        }

        composeRule.runOnIdle { viewModel.refreshTasks() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("batch-task-static-batch").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { viewModel.loadDetail("static-batch") }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("batch-detail").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("batch-expanded-layout").assertExists()
        composeRule.onNodeWithTag("batch-list").assertExists()
        composeRule.onNodeWithTag("batch-subtask-list").assertExists()
        composeRule.onNodeWithTag("batch-detail").assertExists()
    }

    @Test
    fun mediumBatchScreenShowsTwoPaneLayout() {
        val viewModel = BatchViewModel(StaticBatchRepository(), autoRefresh = false)
        composeRule.setContent {
            BiliNoteTheme {
                Box(Modifier.size(800.dp, 800.dp)) {
                    com.bilinote.android.feature.batch.BatchScreen(
                        viewModel = viewModel,
                        windowWidthSizeClass = calculateFromSize(DpSize(800.dp, 800.dp)).widthSizeClass,
                        onOpenDetail = {},
                        onOpenReader = {},
                        onExport = {},
                    )
                }
            }
        }

        composeRule.runOnIdle { viewModel.refreshTasks() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("batch-task-static-batch").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { viewModel.loadDetail("static-batch") }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("batch-detail").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("batch-medium-layout").assertExists()
        composeRule.onNodeWithTag("batch-list").assertExists()
        composeRule.onNodeWithTag("batch-detail").assertExists()
    }

    @Test
    fun createSubmitIsDisabledWithoutPreviewSelectionOrRequiredModels() {
        val viewModel = BatchViewModel(StaticBatchRepository(), autoRefresh = false)
        composeRule.setContent {
            BiliNoteTheme {
                com.bilinote.android.feature.batch.BatchCreateSheet(
                    viewModel = viewModel,
                    onDismissRequest = {},
                )
            }
        }

        composeRule.onNodeWithTag("batch-create-submit").assertIsNotEnabled()
    }

    @Test
    fun mediumBatchRouteKeepsNavigationAndUnconfiguredStateWithoutBackend() {
        setContentFor(800.dp, 800.dp)

        composeRule.onNodeWithContentDescription("navigation-rail:Batch").performClick()
        composeRule.onNodeWithTag("service-not-configured").assertExists()
    }

    private fun setContentFor(width: Dp, height: Dp) {
        val widthSizeClass = calculateFromSize(DpSize(width, height)).widthSizeClass
        composeRule.setContent {
            BiliNoteTheme {
                Box(Modifier.size(width, height)) {
                    AppNavHost(widthSizeClass)
                }
            }
        }
    }
}

private class StaticBatchRepository : BatchRepository {
    private val task = BatchTask(
        id = "static-batch",
        title = "静态测试合集",
        status = "SUCCESS",
        config = BatchConfig(),
    )

    override val serviceBaseUrl: String = "http://static.test"

    override suspend fun previewBilibili(sourceUrl: String): BatchPreview = BatchPreview()

    override suspend fun previewLocal(files: List<MultipartBody.Part>): BatchPreview = BatchPreview()

    override suspend fun previewAppendBilibili(batchId: String, sourceUrl: String): BatchPreview = BatchPreview()

    override suspend fun previewAppendLocal(batchId: String, files: List<MultipartBody.Part>): BatchPreview = BatchPreview()

    override suspend fun batchTasks(page: Int, pageSize: Int): BatchTaskPage =
        BatchTaskPage(items = listOf(task), total = 1)

    override suspend fun createBatch(request: BatchCreateRequest): BatchTask = task

    override suspend fun batchDetail(batchId: String): BatchTask = task

    override suspend fun updateBatch(batchId: String, request: BatchUpdateRequest): BatchTask = task

    override suspend fun appendBatchItems(batchId: String, request: BatchAppendRequest): BatchAppendResult =
        BatchAppendResult(task = task)

    override suspend fun batchItems(batchId: String): List<BatchItem> = emptyList()

    override suspend fun batchEvents(batchId: String, itemId: String): List<BatchEvent> = emptyList()

    override suspend fun retryBatchItem(batchId: String, itemId: String): BatchItem = BatchItem(id = itemId)

    override suspend fun deleteBatchItem(batchId: String, itemId: String): BatchDeleteResult = BatchDeleteResult(task)

    override suspend fun deleteBatchItems(batchId: String, itemIds: List<String>): BatchDeleteResult = BatchDeleteResult(task)

    override suspend fun exportBatch(batchId: String, itemIds: List<String>?): BatchExport =
        BatchExport(ByteArray(0), "static.zip", null)

    override suspend fun exportBatchItem(batchId: String, itemId: String): BatchExport =
        BatchExport(ByteArray(0), "static-item.zip", null)
}
