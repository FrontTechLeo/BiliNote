package com.bilinote.android.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.bilinote.android.database.entity.TaskEntity
import com.bilinote.android.network.RepositoryRefreshResult
import com.bilinote.android.network.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactLayoutShowsListWithoutDetailPane() {
        val viewModel = viewModelWithTasks()
        setContent(viewModel, WindowWidthSizeClass.Compact)

        composeRule.onNodeWithTag("library-screen").assertExists()
        composeRule.onNodeWithTag("library-list").assertExists()
        composeRule.onNodeWithTag("library-detail").assertDoesNotExist()
        composeRule.onNodeWithTag("library-detail-empty").assertDoesNotExist()
    }

    @Test
    fun mediumLayoutShowsListAndSuccessfulActions() {
        val viewModel = viewModelWithTasks()
        viewModel.selectTask("task-success")
        setContent(viewModel, WindowWidthSizeClass.Medium)

        composeRule.onNodeWithTag("library-list").assertExists()
        composeRule.onNodeWithTag("library-detail").assertExists()
        composeRule.onNodeWithTag("library-open-reader-task-success").assertExists()
        composeRule.onNodeWithTag("library-export-task-success").assertExists()
        composeRule.onNodeWithTag("library-regenerate-task-success").assertDoesNotExist()
    }

    @Test
    fun expandedLayoutShowsFailedRegenerateWithoutReaderOrExport() {
        val viewModel = viewModelWithTasks()
        viewModel.selectTask("task-failed")
        setContent(viewModel, WindowWidthSizeClass.Expanded)

        composeRule.onNodeWithTag("library-list").assertExists()
        composeRule.onNodeWithTag("library-detail").assertExists()
        composeRule.onNodeWithTag("library-regenerate-task-failed").assertExists()
        composeRule.onNodeWithTag("library-open-reader-task-failed").assertDoesNotExist()
        composeRule.onNodeWithTag("library-export-task-failed").assertDoesNotExist()
    }

    private fun setContent(viewModel: LibraryViewModel, widthSizeClass: WindowWidthSizeClass) {
        composeRule.setContent {
            Box(Modifier.size(1_280.dp, 800.dp)) {
                LibraryScreen(
                    viewModel = viewModel,
                    windowWidthSizeClass = widthSizeClass,
                    onOpenReader = {},
                    onExport = {},
                    onRegenerate = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun viewModelWithTasks(): LibraryViewModel = LibraryViewModel(
        dataSource = FakeLibraryTaskDataSource(
            listOf(
                task("task-success", "SUCCESS", hasResult = true),
                task("task-failed", "FAILED", hasResult = false),
            ),
        ),
        scope = CoroutineScope(Dispatchers.Unconfined),
        autoRefresh = false,
    )

    private fun task(taskId: String, status: String, hasResult: Boolean) = TaskEntity(
        serviceBaseUrl = "https://service-a",
        taskId = taskId,
        title = taskId,
        status = status,
        message = null,
        platform = "bilibili",
        sourceUrl = "https://example.com/$taskId",
        hasResult = hasResult,
        createdAt = 1L,
        updatedAt = 1L,
        markdown = null,
    )
}

private class FakeLibraryTaskDataSource(
    tasks: List<TaskEntity>,
) : LibraryTaskDataSource {
    private val state = MutableStateFlow(tasks)

    override fun observeTasks(): Flow<List<TaskEntity>> = state

    override suspend fun refresh(): RepositoryRefreshResult<List<TaskEntity>> =
        RepositoryRefreshResult(state.value, SyncStatus.Online)
}
