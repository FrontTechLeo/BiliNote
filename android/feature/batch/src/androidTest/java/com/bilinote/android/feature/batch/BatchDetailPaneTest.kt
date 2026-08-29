package com.bilinote.android.feature.batch

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import com.bilinote.android.core.designsystem.BiliNoteTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchDetailPaneTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun detailRemovesAppendUiAndKeepsTaskActions() {
        setContent()

        composeRule.onNodeWithText("追加条目").assertDoesNotExist()
        composeRule.onNodeWithText("预览").assertDoesNotExist()
        composeRule.onNodeWithText("追加").assertDoesNotExist()
        composeRule.onNodeWithText("编辑").assertExists()
        composeRule.onNodeWithText("导出合集").assertExists()
        composeRule.onNodeWithText("日志").assertExists()
        composeRule.onNodeWithText("阅读").assertExists()
        composeRule.onNodeWithText("导出").assertExists()
        composeRule.onNodeWithText("重试").assertExists()
        composeRule.onNodeWithText("删除").assertExists()
        composeRule.onNodeWithText("编辑").performClick()
        composeRule.onNodeWithText("图片压缩级别").assertExists()
        composeRule.onNodeWithText("高压缩（JPEG/720p）").assertExists()
        composeRule.onNodeWithText("中等（JPEG）").assertExists()
        composeRule.onNodeWithText("原图（PNG）").assertExists()
    }

    private fun setContent() {
        composeRule.setContent {
            BiliNoteTheme {
                Box(Modifier.size(720.dp)) {
                    BatchDetailPane(
                        uiState = BatchUiState(
                            selectedTask = BatchTask(
                                id = "batch-1",
                                title = "阅读合集",
                                status = "RUNNING",
                                totalCount = 2,
                                completedCount = 1,
                                runningCount = 1,
                                progress = 0.5,
                                config = BatchConfig(quality = "high", style = "教程"),
                            ),
                            items = listOf(
                                BatchItem(
                                    id = "done",
                                    taskId = "task-done",
                                    title = "已完成任务",
                                    status = "SUCCESS",
                                    position = 1,
                                ),
                                BatchItem(
                                    id = "failed",
                                    title = "失败任务",
                                    status = "FAILED",
                                    position = 2,
                                ),
                            ),
                        ),
                        onRefresh = {},
                        onUpdate = { _, _ -> },
                        onExport = { _, _ -> },
                        onExportItem = {},
                        onOpenReader = {},
                        onRetry = {},
                        onDelete = {},
                        onLoadEvents = {},
                    )
                }
            }
        }
    }
}
