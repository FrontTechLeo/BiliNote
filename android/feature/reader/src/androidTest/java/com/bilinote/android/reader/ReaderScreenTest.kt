package com.bilinote.android.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.bilinote.android.core.designsystem.BiliNoteTheme
import com.bilinote.android.database.entity.ReadingProgressEntity
import com.bilinote.android.database.entity.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersHeadingAndParagraphFromRealViewModel() {
        val viewModel = viewModelFor("# Heading\n\nA paragraph.")
        setContent(viewModel, WindowWidthSizeClass.Compact)

        composeRule.onNodeWithTag("markdown-heading-1").assertExists()
        composeRule.onNodeWithTag("markdown-paragraph").assertExists()
    }

    @Test
    fun contentTimeLinkCallsCallbackWithSeconds() {
        var seconds = -1
        setContent(
            viewModelFor("Watch *Content-[01:23]*"),
            WindowWidthSizeClass.Compact,
            onOpenTimeLink = { seconds = it },
        )

        composeRule.onNodeWithTag("markdown-time-link-83").assertExists().performClick()
        assertEquals(83, seconds)
    }

    @Test
    fun missingLocalTaskIsRefreshedThroughDataSource() {
        val refreshed = task("task-1", "# From server")
        val dataSource = FakeReaderDataSource(localTask = null, refreshedTask = refreshed)
        val viewModel = ReaderViewModel(
            taskId = "task-1",
            serviceBaseUrl = "https://service-a",
            dataSource = dataSource,
            progressStore = FakeProgressStore(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        setContent(viewModel, WindowWidthSizeClass.Compact)

        composeRule.onNodeWithTag("markdown-heading-1").assertExists()
        assertEquals("PENDING", dataSource.refreshedInput?.status)
    }

    @Test
    fun imageFailureShowsRetryAction() {
        val viewModel = viewModelFor("![Screenshot](http://127.0.0.1:1/missing.png)")
        setContent(viewModel, WindowWidthSizeClass.Compact)

        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithTag("markdown-image-retry").assertExists()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithTag("markdown-image-retry").performClick()
        composeRule.onNodeWithTag("markdown-image-retry").assertExists()
    }

    @Test
    fun backButtonCallsInjectedCallback() {
        var backCalls = 0
        setContent(
            viewModelFor("# Heading"),
            WindowWidthSizeClass.Compact,
            onBack = { backCalls++ },
        )

        composeRule.onNodeWithTag("reader-back").performClick()
        assertEquals(1, backCalls)
    }

    @Test
    fun compactIsReaderOnly() {
        val related = listOf(task("related", "# Related"))
        setContent(viewModelFor("# Compact"), WindowWidthSizeClass.Compact, relatedTasks = related)
        composeRule.onNodeWithTag("reader-related-tasks").assertDoesNotExist()
    }

    @Test
    fun expandedKeepsRelatedTasksAndReaderOnRight() {
        val related = listOf(task("related", "# Related"))
        setContent(viewModelFor("# Expanded"), WindowWidthSizeClass.Expanded, relatedTasks = related)
        composeRule.onNodeWithTag("reader-related-tasks").assertExists()
        composeRule.onNodeWithTag("reader-content").assertExists()
    }

    @Test
    fun restoresProgressOnlyAfterContentIsMeasured() {
        val progress = FakeProgressStore(
            ReadingProgressEntity("task-1", "https://service-a", 120, 0.2, 1L),
        )
        val viewModel = viewModelFor("# Heading\n\nA paragraph.", progress)
        setContent(viewModel, WindowWidthSizeClass.Compact)

        composeRule.onNodeWithTag("reader-content-restored").assertExists()
        composeRule.waitUntil(5_000) { progress.observedOffset == 120 }
        assertEquals(120, progress.observedOffset)
    }

    @Test
    fun exportButtonDeliversViewModelExportToInjectedCallback() {
        var exported: ReaderExport? = null
        val viewModel = viewModelFor("# Heading")
        setContent(
            viewModel,
            WindowWidthSizeClass.Compact,
            onExport = { exported = it },
        )

        composeRule.onNodeWithTag("reader-export").performClick()
        composeRule.waitUntil(5_000) { exported != null }
        assertEquals("note-task-1.md", exported?.fileName)
        assertEquals("# exported", exported?.body?.decodeToString())
        assertTrue(viewModel.uiState.value.exportState is ReaderExportState.Idle)
    }

    @Test
    fun exportButtonDeliversZipExportToInjectedCallback() {
        var exported: ReaderExport? = null
        val viewModel = ReaderViewModel(
            taskId = "task-1",
            serviceBaseUrl = "https://service-a",
            dataSource = FakeReaderDataSource(
                localTask = task("task-1", "# Heading"),
                exportResult = ReaderExport(byteArrayOf(0x50, 0x4b), "note-task-1.zip", "application/zip"),
            ),
            progressStore = FakeProgressStore(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        setContent(viewModel, WindowWidthSizeClass.Compact, onExport = { exported = it })

        composeRule.onNodeWithTag("reader-export").performClick()
        composeRule.waitUntil(5_000) { exported != null }
        assertTrue(exported?.fileName?.endsWith(".zip") == true)
        assertEquals(byteArrayOf(0x50, 0x4b).toList(), exported?.body?.toList())
    }

    @Test
    fun resolvesRootImagePathsOutsideBackendApiPrefix() {
        assertEquals(
            "https://service-a/static/screenshot.png",
            resolveImageUrl("/static/screenshot.png", "https://service-a/api"),
        )
        assertEquals(
            "https://service-a/api/uploads/screenshot.png",
            resolveImageUrl("uploads/screenshot.png", "https://service-a/api"),
        )
    }

    @Test
    fun clearingViewModelDoesNotCancelInjectedExternalScope() {
        val external = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = viewModelFor("# Heading", scope = external)
        val store = ViewModelStore()
        store.put("reader", viewModel)

        store.clear()

        assertTrue(external.coroutineContext[Job]?.isActive == true)
        var ran = false
        external.launch { ran = true }
        assertTrue(ran)
        external.cancel()
    }

    private fun setContent(
        viewModel: ReaderViewModel,
        widthSizeClass: WindowWidthSizeClass,
        onBack: () -> Unit = {},
        onExport: (ReaderExport) -> Unit = {},
        onOpenTimeLink: (Int) -> Unit = {},
        relatedTasks: List<TaskEntity> = emptyList(),
    ) {
        composeRule.setContent {
            BiliNoteTheme {
                Box(Modifier.size(1_280.dp, 800.dp)) {
                    ReaderScreen(
                        viewModel = viewModel,
                        windowWidthSizeClass = widthSizeClass,
                        onBack = onBack,
                        onOpenSource = {},
                        onExport = onExport,
                        onOpenTimeLink = onOpenTimeLink,
                        relatedTasks = relatedTasks,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun viewModelFor(
        markdown: String,
        progressStore: ProgressStore = FakeProgressStore(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): ReaderViewModel = ReaderViewModel(
        taskId = "task-1",
        serviceBaseUrl = "https://service-a",
        dataSource = FakeReaderDataSource(localTask = task("task-1", markdown)),
        progressStore = progressStore,
        scope = scope,
    )

    private fun task(taskId: String, markdown: String) = TaskEntity(
        serviceBaseUrl = "https://service-a",
        taskId = taskId,
        title = taskId,
        status = "SUCCESS",
        message = null,
        platform = "bilibili",
        sourceUrl = "https://example.com/$taskId",
        hasResult = true,
        createdAt = 1L,
        updatedAt = 1L,
        markdown = markdown,
    )
}

private class FakeReaderDataSource(
    private val localTask: TaskEntity? = null,
    private val refreshedTask: TaskEntity? = localTask,
    private val exportResult: ReaderExport = ReaderExport(
        "# exported".encodeToByteArray(),
        "note-task.md",
        "text/markdown",
    ),
) : ReaderDataSource {
    var refreshedInput: TaskEntity? = null

    override suspend fun loadTask(taskId: String, serviceBaseUrl: String): TaskEntity? =
        localTask?.takeIf { it.taskId == taskId && it.serviceBaseUrl == serviceBaseUrl }

    override suspend fun refreshTask(task: TaskEntity): TaskEntity {
        refreshedInput = task
        return refreshedTask ?: task
    }

    override suspend fun export(taskId: String, serviceBaseUrl: String): ReaderExport =
        exportResult.copy(fileName = exportResult.fileName.replace("task", taskId))
}

private class FakeProgressStore(
    initial: ReadingProgressEntity? = null,
) : ProgressStore {
    private val state = MutableStateFlow(initial)
    var observedOffset: Int? = null

    override fun observe(taskId: String, serviceBaseUrl: String): Flow<ReadingProgressEntity?> = state

    override suspend fun saveThrottled(progress: ReadingProgressEntity) {
        if (progress.scrollOffset > 0) observedOffset = progress.scrollOffset
        state.value = progress
    }

    override suspend fun saveNow(progress: ReadingProgressEntity) {
        observedOffset = progress.scrollOffset
        state.value = progress
    }
}
