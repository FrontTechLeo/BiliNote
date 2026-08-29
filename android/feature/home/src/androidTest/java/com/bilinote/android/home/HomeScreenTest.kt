package com.bilinote.android.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.bilinote.android.core.designsystem.BiliNoteTheme
import com.bilinote.android.network.dto.GenerateNoteResponse
import com.bilinote.android.network.dto.SingleTaskStatusDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactLayoutShowsHomeSectionsAndCanSubmitComposer() {
        val repository = FakeHomeRepository(
            activeTasks = listOf(sampleTask("active-1", "正在处理的视频", "RUNNING", false)),
            recentTasks = listOf(sampleTask("recent-1", "最近笔记", "SUCCESS", true)),
        )
        val viewModel = HomeViewModel(repository)
        setContent(viewModel, WindowWidthSizeClass.Compact)

        composeRule.onNodeWithTag("home-compact-layout").assertExists()
        composeRule.onNodeWithTag("home-active-task-card").assertExists()
        composeRule.onNodeWithTag("home-continue-reading").assertExists()
        composeRule.onNodeWithTag("home-recent-notes").assertExists()
        composeRule.onNodeWithTag("home-create-button").assertExists().performClick()
        composeRule.onNodeWithTag("note-composer-sheet").assertExists()
        assertComposerFieldsExist()

        composeRule.onNodeWithContentDescription("note-composer-link").performTextInput("https://example.com/video")
        composeRule.onNodeWithTag("note-composer-submit").performClick()
        composeRule.waitUntil(3_000) { repository.generatedRequests.isNotEmpty() }
        assertTrue(repository.generatedRequests.isNotEmpty())
    }

    @Test
    fun expandedLayoutKeepsActiveAndRecentSectionsVisible() {
        val repository = FakeHomeRepository(
            activeTasks = listOf(sampleTask("active-1", "正在处理的视频", "RUNNING", false)),
            recentTasks = listOf(sampleTask("recent-1", "最近笔记", "SUCCESS", true)),
        )
        setContent(HomeViewModel(repository), WindowWidthSizeClass.Expanded, 1_280.dp)

        composeRule.onNodeWithTag("home-expanded-layout").assertExists()
        composeRule.onNodeWithTag("home-active-task-card").assertExists()
        composeRule.onNodeWithTag("home-recent-notes").assertExists()
    }

    @Test
    fun failedTaskShowsFailureStateWithoutReaderAction() {
        val failedTask = sampleTask("failed-1", "失败的视频", "FAILED", false).copy(message = "转码失败")
        setContent(
            HomeViewModel(
                FakeHomeRepository(
                    recentTasks = listOf(failedTask),
                ),
            ),
            WindowWidthSizeClass.Compact,
        )

        composeRule.onNodeWithText("失败").assertExists()
        composeRule.onNodeWithText("转码失败").assertExists()
        composeRule.onNodeWithText("阅读").assertDoesNotExist()
        composeRule.onNodeWithText("重试").assertExists()
    }

    private fun assertComposerFieldsExist() {
        composeRule.onNodeWithText("平台").assertExists()
        composeRule.onNodeWithText("视频链接或本地文件").assertExists()
        composeRule.onNodeWithText("提供商").assertExists()
        composeRule.onNodeWithText("模型").assertExists()
        composeRule.onNodeWithText("质量").assertExists()
        composeRule.onNodeWithText("风格").assertExists()
        composeRule.onNodeWithText("视频理解").assertExists()
        composeRule.onNodeWithText("视频间隔").assertExists()
        composeRule.onNodeWithText("网格尺寸").assertExists()
        composeRule.onNodeWithText("图片压缩级别").assertExists()
        composeRule.onNodeWithText("高压缩（JPEG/720p）").assertExists()
        composeRule.onNodeWithText("中等（JPEG）").assertExists()
        composeRule.onNodeWithText("原图（PNG）").assertExists()
        composeRule.onNodeWithText("输出格式").assertExists()
        composeRule.onNodeWithText("额外要求").assertExists()
    }

    private fun setContent(
        viewModel: HomeViewModel,
        widthSizeClass: WindowWidthSizeClass,
        width: androidx.compose.ui.unit.Dp = 390.dp,
    ) {
        composeRule.setContent {
            BiliNoteTheme {
                Box(Modifier.size(width, 800.dp)) {
                    HomeScreen(
                        viewModel = viewModel,
                        windowWidthSizeClass = widthSizeClass,
                        onOpenReader = {},
                    )
                }
            }
        }
    }

    private fun sampleTask(
        taskId: String,
        title: String,
        status: String,
        hasResult: Boolean,
    ) = HomeTask(
        taskId = taskId,
        title = title,
        status = status,
        message = null,
        platform = "bilibili",
        sourceUrl = "https://example.com/$taskId",
        hasResult = hasResult,
    )
}

private class FakeHomeRepository(
    activeTasks: List<HomeTask> = emptyList(),
    recentTasks: List<HomeTask> = emptyList(),
) : HomeRepository {
    private val active = MutableStateFlow(activeTasks)
    private val recent = MutableStateFlow(recentTasks)
    val generatedRequests = mutableListOf<com.bilinote.android.network.dto.GenerateNoteRequest>()

    override fun observeActiveTasks(): Flow<List<HomeTask>> = active

    override fun observeRecentTasks(): Flow<List<HomeTask>> = recent

    override suspend fun generateNote(
        request: com.bilinote.android.network.dto.GenerateNoteRequest,
    ): GenerateNoteResponse {
        generatedRequests += request
        return GenerateNoteResponse(taskId = "task-1", status = "PENDING")
    }

    override suspend fun taskStatus(taskId: String): SingleTaskStatusDto =
        SingleTaskStatusDto(taskId = taskId, status = "SUCCESS")
}
