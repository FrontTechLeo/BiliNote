package com.bilinote.android.library

import androidx.lifecycle.ViewModelStore
import com.bilinote.android.database.entity.TaskEntity
import com.bilinote.android.network.RepositoryRefreshResult
import com.bilinote.android.network.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val viewModelStore = ViewModelStore()
    private val testScopes = mutableListOf<CoroutineScope>()
    private var nextViewModelKey = 0

    @After
    fun clearViewModelsAndScopes() {
        try {
            viewModelStore.clear()
        } finally {
            testScopes.forEach(CoroutineScope::cancel)
            testScopes.clear()
        }
    }

    @Test
    fun refreshSuccessWritesAndDisplaysTasks() = runTest {
        val source = FakeLibraryTaskDataSource()
        val viewModel = createViewModel(source, testScheduler)

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf("task-success"), source.cached.value.map { it.taskId })
        assertEquals(listOf("task-success"), viewModel.uiState.value.tasks.map { it.taskId })
        assertEquals(SyncStatus.Online, viewModel.uiState.value.syncStatus)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun refreshFailureKeepsCachedTasksAndShowsOfflineError() = runTest {
        val cachedTask = task("task-cached", status = "SUCCESS", hasResult = true)
        val source = FakeLibraryTaskDataSource(
            initial = listOf(cachedTask),
            result = RepositoryRefreshResult(
                data = listOf(cachedTask),
                status = SyncStatus.Offline,
                errorMessage = "network unavailable",
            ),
        )
        val viewModel = createViewModel(source, testScheduler)

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf(cachedTask.taskId), viewModel.uiState.value.tasks.map { it.taskId })
        assertEquals(SyncStatus.Offline, viewModel.uiState.value.syncStatus)
        assertEquals("network unavailable", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun failedTaskCannotOpenReaderOrExport() = runTest {
        val viewModel = createViewModel(
            FakeLibraryTaskDataSource(initial = listOf(task("task-failed", "FAILED", false))),
            testScheduler,
        )

        advanceUntilIdle()

        val failedTask = viewModel.uiState.value.tasks.single()
        assertFalse(failedTask.canOpenReader)
        assertFalse(failedTask.canExport)
        assertTrue(failedTask.canRegenerate)
    }

    @Test
    fun failureStatusIsFailedAndCanRegenerate() = runTest {
        val viewModel = createViewModel(
            FakeLibraryTaskDataSource(initial = listOf(task("task-failure", "FAILURE", false))),
            testScheduler,
        )

        advanceUntilIdle()

        val failedTask = viewModel.uiState.value.tasks.single()
        assertEquals(LibraryTaskAvailability.Failed, failedTask.availability)
        assertTrue(failedTask.canRegenerate)
        assertFalse(failedTask.canOpenReader)
        assertFalse(failedTask.canExport)
    }

    @Test
    fun processingTaskCannotOpenReaderExportOrRegenerate() = runTest {
        val viewModel = createViewModel(
            FakeLibraryTaskDataSource(initial = listOf(task("task-processing", "RUNNING", true))),
            testScheduler,
        )

        advanceUntilIdle()

        val processingTask = viewModel.uiState.value.tasks.single()
        assertEquals(LibraryTaskAvailability.Processing, processingTask.availability)
        assertFalse(processingTask.canOpenReader)
        assertFalse(processingTask.canExport)
        assertFalse(processingTask.canRegenerate)
    }

    @Test
    fun selectTaskAcceptsCurrentTaskAndKeepsOrClearsSelectionWithCacheChanges() = runTest {
        val first = task("task-first", "SUCCESS", true)
        val second = task("task-second", "RUNNING", false)
        val source = FakeLibraryTaskDataSource(initial = listOf(first, second))
        val viewModel = createViewModel(source, testScheduler)
        advanceUntilIdle()

        viewModel.selectTask("task-first")
        assertEquals("task-first", viewModel.uiState.value.selectedTaskId)

        source.cached.value = listOf(first, second)
        advanceUntilIdle()
        assertEquals("task-first", viewModel.uiState.value.selectedTaskId)

        viewModel.selectTask("missing-task")
        assertEquals(null, viewModel.uiState.value.selectedTaskId)

        viewModel.selectTask("task-first")
        source.cached.value = listOf(second)
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.selectedTaskId)
    }

    @Test
    fun successfulTaskCanOpenReader() = runTest {
        val viewModel = createViewModel(
            FakeLibraryTaskDataSource(initial = listOf(task("task-success", "SUCCESS", true))),
            testScheduler,
        )

        advanceUntilIdle()

        val successfulTask = viewModel.uiState.value.tasks.single()
        assertTrue(successfulTask.canOpenReader)
        assertTrue(successfulTask.canExport)
    }

    @Test
    fun clearingViewModelStopsChildTasksButKeepsCallerScopeActive() = runTest {
        val externalJob = SupervisorJob()
        val externalScope = CoroutineScope(externalJob + StandardTestDispatcher(testScheduler))
        testScopes += externalScope
        val first = task("task-first", "RUNNING", false)
        val second = task("task-second", "RUNNING", false)
        val source = LifecycleLibraryTaskDataSource(initial = listOf(first))
        val viewModel = LibraryViewModel(
            source,
            scope = externalScope,
            autoRefresh = false,
        )
        runCurrent()
        assertEquals("task-first", viewModel.uiState.value.tasks.single().taskId)

        viewModel.refresh()
        runCurrent()
        assertTrue(source.refreshStarted)

        viewModelStore.put("library", viewModel)
        viewModelStore.clear()
        runCurrent()

        source.cached.value = listOf(second)
        runCurrent()

        assertEquals("task-first", viewModel.uiState.value.tasks.single().taskId)
        assertTrue(source.refreshCancelled)
        assertTrue(externalJob.isActive)
        externalScope.cancel()
    }

    private fun createViewModel(
        dataSource: LibraryTaskDataSource,
        scheduler: TestCoroutineScheduler,
    ): LibraryViewModel {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        testScopes += scope
        return LibraryViewModel(dataSource, scope = scope, autoRefresh = false).also { viewModel ->
            viewModelStore.put("library-${nextViewModelKey++}", viewModel)
        }
    }

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
    initial: List<TaskEntity> = emptyList(),
    private val result: RepositoryRefreshResult<List<TaskEntity>> = RepositoryRefreshResult(
        data = listOf(
            TaskEntity(
                serviceBaseUrl = "https://service-a",
                taskId = "task-success",
                title = "Successful task",
                status = "SUCCESS",
                message = null,
                platform = "bilibili",
                sourceUrl = "https://example.com/video",
                hasResult = true,
                createdAt = 1L,
                updatedAt = 1L,
                markdown = null,
            ),
        ),
        status = SyncStatus.Online,
    ),
) : LibraryTaskDataSource {
    val cached = MutableStateFlow(initial)

    override fun observeTasks(): Flow<List<TaskEntity>> = cached

    override suspend fun refresh(): RepositoryRefreshResult<List<TaskEntity>> {
        cached.value = result.data
        return result
    }
}

private class LifecycleLibraryTaskDataSource(
    initial: List<TaskEntity>,
) : LibraryTaskDataSource {
    val cached = MutableStateFlow(initial)
    var refreshStarted = false
    var refreshCancelled = false

    override fun observeTasks(): Flow<List<TaskEntity>> = cached

    override suspend fun refresh(): RepositoryRefreshResult<List<TaskEntity>> =
        suspendCancellableCoroutine { continuation ->
            refreshStarted = true
            continuation.invokeOnCancellation { refreshCancelled = true }
        }
}
