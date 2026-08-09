package com.bilinote.android.feature.batch

import com.bilinote.android.network.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatchViewModelTest {
    @Test
    fun `create batch rejects zero preview selection without repository call`() = runTest {
        val repository = FakeBatchRepository()
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.createBatch(config = BatchConfig(format = listOf("link")))
        advanceUntilIdle()

        assertEquals(0, repository.createCalls)
        assertEquals("请选择至少一个条目", viewModel.uiState.value.actionError)
    }

    @Test
    fun `create batch request items take precedence over preview selection`() = runTest {
        val repository = FakeBatchRepository()
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)
        val requestedItem = BatchPreviewItem(id = "requested", sourceUrl = "requested-url")

        viewModel.createBatch(
            BatchCreateRequest(
                title = "Requested",
                items = listOf(requestedItem),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(requestedItem), repository.createRequests.single().items)
        assertEquals("Requested", repository.createRequests.single().title)
    }

    @Test
    fun `create batch falls back to selected preview items when request items are empty`() = runTest {
        val repository = FakeBatchRepository(preview = preview("selected"))
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.previewBilibili("url")
        advanceUntilIdle()
        viewModel.selectAll()
        viewModel.createBatch(BatchCreateRequest(title = "Fallback"))
        advanceUntilIdle()

        assertEquals(listOf("selected"), repository.createRequests.single().items.map { it.id })
    }

    @Test
    fun `select all and clear selection update selected preview ids`() = runTest {
        val repository = FakeBatchRepository(preview = preview("1", "2"))
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.previewBilibili("https://example.com/list")
        advanceUntilIdle()
        viewModel.selectAll()
        assertEquals(setOf("1-0", "2-1"), viewModel.uiState.value.selectedPreviewIds)

        viewModel.clearSelection()
        assertTrue(viewModel.uiState.value.selectedPreviewIds.isEmpty())
    }

    @Test
    fun `clear preview removes preview and selection but keeps other state`() = runTest {
        val repository = FakeBatchRepository(preview = preview("1"))
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)
        viewModel.selectBatch("b1")
        viewModel.previewBilibili("url")
        advanceUntilIdle()
        viewModel.selectAll()

        viewModel.clearPreview()

        assertEquals(null, viewModel.uiState.value.preview)
        assertTrue(viewModel.uiState.value.selectedPreviewIds.isEmpty())
        assertEquals("b1", viewModel.uiState.value.selectedBatchId)
    }

    @Test
    fun `clear preview cancels an in progress preview`() = runTest {
        val repository = FakeBatchRepository(
            blockingRepositoryCall = "preview",
            holdFirstBlockingCallUntilRelease = true,
        )
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.previewBilibili("url")
        runCurrent()
        repository.blockingCallStarted.await()

        viewModel.clearPreview()

        assertEquals(null, viewModel.uiState.value.preview)
        assertFalse(viewModel.uiState.value.isLoading)
        repository.firstBlockingCallRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.preview)
    }

    @Test
    fun `preview selection keys preserve idless items for create and append`() = runTest {
        val items = listOf(
            BatchPreviewItem(sourceUrl = "source-url"),
            BatchPreviewItem(title = "title-only"),
            BatchPreviewItem(),
        )
        val repository = FakeBatchRepository(preview = BatchPreview(items = items))
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.previewBilibili("url")
        advanceUntilIdle()
        assertEquals("source-url-0", viewModel.previewSelectionKey(items[0], 0))
        assertEquals("title-only-1", viewModel.previewSelectionKey(items[1], 1))
        assertEquals("preview-2", viewModel.previewSelectionKey(items[2], 2))

        items.forEachIndexed { index, item ->
            viewModel.togglePreviewSelection(viewModel.previewSelectionKey(item, index))
        }
        viewModel.createBatch()
        advanceUntilIdle()
        assertEquals(items, repository.createRequests.single().items)

        viewModel.selectAll()
        viewModel.selectBatch("b1")
        viewModel.appendSelected()
        advanceUntilIdle()
        assertEquals(items, repository.appendRequests.single().items)
    }

    @Test
    fun `duplicate idless preview items have independent selection keys`() = runTest {
        val items = listOf(
            BatchPreviewItem(sourceUrl = "same-source"),
            BatchPreviewItem(sourceUrl = "same-source"),
            BatchPreviewItem(title = "same-title"),
            BatchPreviewItem(title = "same-title"),
        )
        val repository = FakeBatchRepository(preview = BatchPreview(items = items))
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.previewBilibili("url")
        advanceUntilIdle()
        val firstKey = viewModel.previewSelectionKey(items[0], 0)
        val lastKey = viewModel.previewSelectionKey(items[3], 3)
        assertTrue(firstKey != viewModel.previewSelectionKey(items[1], 1))
        assertTrue(lastKey != viewModel.previewSelectionKey(items[2], 2))

        viewModel.togglePreviewSelection(firstKey)
        viewModel.togglePreviewSelection(lastKey)
        viewModel.createBatch()
        advanceUntilIdle()

        assertEquals(listOf(items[0], items[3]), repository.createRequests.single().items)
    }

    @Test
    fun `duplicate ids have independent selection keys`() = runTest {
        val items = listOf(
            BatchPreviewItem(id = "same"),
            BatchPreviewItem(id = "same"),
        )
        val repository = FakeBatchRepository(preview = BatchPreview(items = items))
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.previewBilibili("url")
        advanceUntilIdle()
        val firstKey = viewModel.previewSelectionKey(items[0], 0)
        val secondKey = viewModel.previewSelectionKey(items[1], 1)
        assertEquals("same-0", firstKey)
        assertEquals("same-1", secondKey)

        viewModel.togglePreviewSelection(firstKey)
        viewModel.createBatch()
        advanceUntilIdle()

        assertEquals(listOf(items[0]), repository.createRequests.single().items)
    }

    @Test
    fun `terminal detail status stops polling`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher + Job())
        val repository = FakeBatchRepository(
            detailSequence = ArrayDeque(listOf(task("b1", "DONE"), task("b1", "RUNNING"))),
        )
        val viewModel = BatchViewModel(repository, scope = scope, autoRefresh = false)

        viewModel.selectBatch("b1")
        viewModel.startDetailPolling()
        advanceTimeBy(3_500)
        advanceUntilIdle()
        val callsAfterTerminal = repository.detailCalls
        advanceTimeBy(7_000)
        advanceUntilIdle()
        viewModel.stopDetailPolling()

        assertEquals(callsAfterTerminal, repository.detailCalls)
        assertEquals("DONE", viewModel.uiState.value.selectedTask?.status)
    }

    @Test
    fun `cancelled polling response cannot overwrite newly selected batch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher + Job())
        val repository = FakeBatchRepository(
            detailSequence = ArrayDeque(listOf(task("a", "RUNNING"))),
            blockingRepositoryCall = "detail",
            holdFirstBlockingCallUntilRelease = true,
            holdFirstBlockingCallNonCancellable = true,
        )
        val viewModel = BatchViewModel(repository, scope = scope, autoRefresh = false)

        viewModel.selectBatch("a")
        viewModel.startDetailPolling()
        advanceTimeBy(3_500)
        runCurrent()
        repository.blockingCallStarted.await()

        viewModel.selectBatch("b")
        repository.firstBlockingCallRelease.complete(Unit)
        advanceUntilIdle()
        viewModel.stopDetailPolling()

        assertEquals("b", viewModel.uiState.value.selectedBatchId)
        assertEquals(null, viewModel.uiState.value.selectedTask)
        assertTrue(viewModel.uiState.value.items.isEmpty())
    }

    @Test
    fun `stale detail response cannot overwrite a newly selected batch`() = runTest {
        val repository = FakeBatchRepository(
            detailSequence = ArrayDeque(listOf(task("a", "RUNNING"), task("b", "DONE"))),
            blockingRepositoryCall = "detail",
            holdFirstBlockingCallUntilRelease = true,
            holdFirstBlockingCallNonCancellable = true,
        )
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.selectBatch("a")
        viewModel.loadDetail()
        runCurrent()
        repository.blockingCallStarted.await()

        viewModel.selectBatch("b")
        viewModel.loadDetail()
        runCurrent()
        repository.firstBlockingCallRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals("b", viewModel.uiState.value.selectedBatchId)
        assertEquals("b", viewModel.uiState.value.selectedTask?.id)
    }

    @Test
    fun `refresh failure keeps service scoped cache and marks offline`() = runTest {
        val repository = FakeBatchRepository(serviceBaseUrl = "https://service-a", initialRefreshError = IllegalStateException("offline"))
        val cache = InMemoryBatchCache(
            mapOf("https://service-a" to listOf(task("cached", "DONE")), "https://service-b" to listOf(task("other", "DONE"))),
        )
        val viewModel = BatchViewModel(repository, cache, scope = this, autoRefresh = false)

        viewModel.refreshTasks()
        advanceUntilIdle()

        assertEquals(listOf("cached"), viewModel.uiState.value.tasks.map { it.id })
        assertEquals(SyncStatus.Offline, viewModel.uiState.value.syncStatus)
        assertEquals("offline", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `refresh failure with empty cache keeps existing tasks`() = runTest {
        val repository = FakeBatchRepository()
        val cache = InMemoryBatchCache(emptyMap())
        val viewModel = BatchViewModel(repository, cache, scope = this, autoRefresh = false)

        viewModel.refreshTasks()
        advanceUntilIdle()
        repository.refreshError = IllegalStateException("offline")
        viewModel.refreshTasks()
        advanceUntilIdle()

        assertEquals(listOf("b1"), viewModel.uiState.value.tasks.map { it.id })
        assertEquals(SyncStatus.Offline, viewModel.uiState.value.syncStatus)
    }

    @Test
    fun `refresh repository access waits for action mutex`() = runTest {
        val repository = FakeBatchRepository(
            blockingRepositoryCall = "refresh",
            holdFirstBlockingCallUntilRelease = true,
        )
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.refreshTasks()
        runCurrent()
        repository.blockingCallStarted.await()
        viewModel.createBatch(items = listOf(previewItem("i1")))
        runCurrent()

        assertEquals(0, repository.createCalls)
        repository.firstBlockingCallRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, repository.createCalls)
    }

    @Test
    fun `detail polling repository access waits for action mutex`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher + Job())
        val repository = FakeBatchRepository(
            detailSequence = ArrayDeque(listOf(task("b1", "DONE"))),
            blockingRepositoryCall = "detail",
            holdFirstBlockingCallUntilRelease = true,
        )
        val viewModel = BatchViewModel(repository, scope = scope, autoRefresh = false)

        viewModel.selectBatch("b1")
        viewModel.startDetailPolling()
        advanceTimeBy(3_500)
        runCurrent()
        repository.blockingCallStarted.await()
        viewModel.deleteItem("i1")
        runCurrent()

        assertTrue(repository.deleteItemCalls.isEmpty())
        repository.firstBlockingCallRelease.complete(Unit)
        advanceUntilIdle()
        viewModel.stopDetailPolling()

        assertEquals(listOf("b1" to "i1"), repository.deleteItemCalls)
    }

    @Test
    fun `starting a new action cancels the previous action`() = runTest {
        val repository = FakeBatchRepository(
            detailSequence = ArrayDeque(listOf(task("b1", "RUNNING"))),
            items = listOf(item("i1"), item("i2")),
        )
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)
        viewModel.selectBatch("b1")
        viewModel.loadDetail()
        runCurrent()
        viewModel.stopDetailPolling()

        viewModel.retryItem("i1")
        runCurrent()
        repository.retryStarted.await()
        viewModel.deleteItem("i2")
        advanceUntilIdle()

        assertTrue(repository.retryCancelled)
        assertEquals(listOf("b1" to "i2"), repository.deleteItemCalls)
    }

    @Test
    fun `starting a new action waits for cancelled repository call to exit`() = runTest {
        val repository = FakeBatchRepository(holdRetryCancellationCleanup = true)
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.selectBatch("b1")
        viewModel.retryItem("i1")
        runCurrent()
        repository.retryStarted.await()
        viewModel.deleteItem("i2")
        runCurrent()
        repository.retryCleanupStarted.await()
        advanceUntilIdle()

        assertTrue(repository.deleteItemCalls.isEmpty())
        assertEquals(1, repository.maxConcurrentRepositoryCalls)

        repository.retryCleanupRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("b1" to "i2"), repository.deleteItemCalls)
        assertEquals(1, repository.maxConcurrentRepositoryCalls)
    }

    @Test
    fun `new action repository call waits until previous action job completes`() = runTest {
        val repository = FakeBatchRepository(holdRetryCancellationCleanup = true)
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.selectBatch("b1")
        viewModel.retryItem("i1")
        runCurrent()
        repository.retryStarted.await()

        viewModel.deleteItem("i2")
        runCurrent()
        repository.retryCleanupStarted.await()
        assertTrue(repository.deleteItemCalls.isEmpty())

        repository.retryCleanupRelease.complete(Unit)
        advanceUntilIdle()

        assertTrue(repository.newActionObservedOldCompletion)
    }

    @Test
    fun `third replacement waits for the first action before starting`() = runTest {
        val repository = FakeBatchRepository(
            blockingRepositoryCall = "create",
            holdFirstBlockingCallUntilRelease = true,
            holdFirstBlockingCallNonCancellable = true,
        )
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)
        viewModel.selectBatch("b1")

        viewModel.createBatch(items = listOf(previewItem("a")))
        runCurrent()
        repository.blockingCallStarted.await()

        viewModel.createBatch(items = listOf(previewItem("b")))
        runCurrent()
        viewModel.previewBilibili("c")
        runCurrent()

        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(repository.previewStarted.isCompleted)

        repository.firstBlockingCallRelease.complete(Unit)
        runCurrent()
        repository.previewStarted.await()
    }

    @Test
    fun `clear preview ignores a late result from a cancellation-ignoring loader`() = runTest {
        val repository = FakeBatchRepository(ignorePreviewCancellation = true)
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.previewBilibili("url")
        runCurrent()
        repository.previewStarted.await()

        viewModel.clearPreview()
        assertEquals(null, viewModel.uiState.value.preview)

        repository.previewRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.preview)
    }

    @Test
    fun `cancelled actions clear their busy states`() = runTest {
        suspend fun assertBusyStateClears(
            repository: FakeBatchRepository,
            viewModel: BatchViewModel,
            start: () -> Unit,
            isBusy: () -> Boolean,
            cancel: () -> Unit,
        ) {
            start()
            runCurrent()
            repository.blockingCallStarted.await()
            assertTrue(isBusy())
            cancel()
            advanceUntilIdle()
            assertFalse(isBusy())
        }

        val createRepository = FakeBatchRepository(blockingRepositoryCall = "create")
        val createViewModel = BatchViewModel(createRepository, scope = this, autoRefresh = false)
        assertBusyStateClears(
            createRepository,
            createViewModel,
            start = { createViewModel.createBatch(items = listOf(previewItem("i1"))) },
            isBusy = { createViewModel.uiState.value.isCreating },
            cancel = { createViewModel.previewBilibili("replacement") },
        )

        val detailRepository = FakeBatchRepository(blockingRepositoryCall = "detail")
        val detailViewModel = BatchViewModel(detailRepository, scope = this, autoRefresh = false)
        detailViewModel.selectBatch("b1")
        assertBusyStateClears(
            detailRepository,
            detailViewModel,
            start = { detailViewModel.loadDetail() },
            isBusy = { detailViewModel.uiState.value.isLoading },
            cancel = { detailViewModel.previewBilibili("replacement") },
        )

        val previewRepository = FakeBatchRepository(blockingRepositoryCall = "preview")
        val previewViewModel = BatchViewModel(previewRepository, scope = this, autoRefresh = false)
        previewViewModel.selectBatch("b1")
        assertBusyStateClears(
            previewRepository,
            previewViewModel,
            start = { previewViewModel.previewBilibili("url") },
            isBusy = { previewViewModel.uiState.value.isLoading },
            cancel = { previewViewModel.deleteItem("i1") },
        )

        val appendRepository = FakeBatchRepository(
            preview = preview("i1"),
            blockingRepositoryCall = "append",
        )
        val appendViewModel = BatchViewModel(appendRepository, scope = this, autoRefresh = false)
        appendViewModel.previewBilibili("url")
        advanceUntilIdle()
        appendViewModel.selectAll()
        appendViewModel.selectBatch("b1")
        assertBusyStateClears(
            appendRepository,
            appendViewModel,
            start = { appendViewModel.appendSelected() },
            isBusy = { appendViewModel.uiState.value.isAppending },
            cancel = { appendViewModel.previewBilibili("replacement") },
        )

        val exportRepository = FakeBatchRepository(blockingRepositoryCall = "export")
        val exportViewModel = BatchViewModel(exportRepository, scope = this, autoRefresh = false)
        exportViewModel.selectBatch("b1")
        assertBusyStateClears(
            exportRepository,
            exportViewModel,
            start = { exportViewModel.exportBatch() },
            isBusy = { exportViewModel.uiState.value.exportState is BatchExportState.Exporting },
            cancel = { exportViewModel.deleteItem("i1") },
        )
    }

    @Test
    fun `cancelled action finally does not clear newer busy state`() = runTest {
        val repository = FakeBatchRepository(
            blockingRepositoryCall = "create",
            holdFirstCancellationCleanup = true,
            holdSecondBlockingCall = true,
        )
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)

        viewModel.createBatch(items = listOf(previewItem("old")))
        runCurrent()
        repository.blockingCallStarted.await()

        viewModel.createBatch(items = listOf(previewItem("new")))
        runCurrent()
        repository.firstCancellationCleanupStarted.await()

        repository.firstCancellationCleanupRelease.complete(Unit)
        runCurrent()
        repository.secondBlockingCallStarted.await()
        assertTrue(viewModel.uiState.value.isCreating)

        repository.secondBlockingCallRelease.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isCreating)
    }

    @Test
    fun `retry and delete actions map repository calls and update selection`() = runTest {
        val repository = FakeBatchRepository(
            detailSequence = ArrayDeque(listOf(task("b1", "RUNNING"))),
            items = listOf(item("i1"), item("i2")),
        )
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)
        viewModel.selectBatch("b1")
        viewModel.loadDetail()
        runCurrent()
        viewModel.stopDetailPolling()

        viewModel.retryItem("i1")
        runCurrent()
        viewModel.deleteItems(listOf("i1", "i2"))
        advanceUntilIdle()

        assertEquals(listOf("b1" to "i1"), repository.retryCalls)
        assertEquals(listOf("b1" to listOf("i1", "i2")), repository.deleteCalls)
        assertTrue(viewModel.uiState.value.selectedPreviewIds.isEmpty())
    }

    @Test
    fun `toggle preview selection only keeps existing item ids`() = runTest {
        val repository = FakeBatchRepository(preview = preview("1"))
        val viewModel = BatchViewModel(repository, scope = this, autoRefresh = false)
        viewModel.previewBilibili("url")
        advanceUntilIdle()

        viewModel.togglePreviewSelection("1-0")
        viewModel.togglePreviewSelection("missing")
        assertEquals(setOf("1-0"), viewModel.uiState.value.selectedPreviewIds)
        viewModel.togglePreviewSelection("1-0")
        assertFalse(viewModel.uiState.value.selectedPreviewIds.contains("1-0"))
    }
}

private class FakeBatchRepository(
    override val serviceBaseUrl: String = "https://service-a",
    private val preview: BatchPreview = preview("1"),
    private val items: List<BatchItem> = emptyList(),
    private val detailSequence: ArrayDeque<BatchTask> = ArrayDeque(listOf(task("b1", "RUNNING"))),
    private val initialRefreshError: Exception? = null,
    private val blockingRepositoryCall: String? = null,
    private val holdRetryCancellationCleanup: Boolean = false,
    private val holdFirstCancellationCleanup: Boolean = false,
    private val holdSecondBlockingCall: Boolean = false,
    private val holdFirstBlockingCallUntilRelease: Boolean = false,
    private val holdFirstBlockingCallNonCancellable: Boolean = false,
    private val ignorePreviewCancellation: Boolean = false,
) : BatchRepository {
    var createCalls = 0
    val createRequests = mutableListOf<BatchCreateRequest>()
    var detailCalls = 0
    var refreshError: Exception? = initialRefreshError
    val blockingCallStarted = CompletableDeferred<Unit>()
    val firstCancellationCleanupStarted = CompletableDeferred<Unit>()
    val firstCancellationCleanupRelease = CompletableDeferred<Unit>()
    val secondBlockingCallStarted = CompletableDeferred<Unit>()
    val secondBlockingCallRelease = CompletableDeferred<Unit>()
    val firstBlockingCallRelease = CompletableDeferred<Unit>()
    val previewStarted = CompletableDeferred<Unit>()
    val previewRelease = CompletableDeferred<Unit>()
    private var blockingCallCount = 0
    val retryCalls = mutableListOf<Pair<String, String>>()
    val retryStarted = CompletableDeferred<Unit>()
    var retryCancelled = false
    val retryCleanupStarted = CompletableDeferred<Unit>()
    val retryCleanupRelease = CompletableDeferred<Unit>()
    val firstActionCompleted = CompletableDeferred<Unit>()
    var newActionObservedOldCompletion = false
    var activeRepositoryCalls = 0
    var maxConcurrentRepositoryCalls = 0
    val deleteCalls = mutableListOf<Pair<String, List<String>>>()
    val deleteItemCalls = mutableListOf<Pair<String, String>>()
    val appendRequests = mutableListOf<BatchAppendRequest>()

    override suspend fun previewBilibili(sourceUrl: String): BatchPreview {
        previewStarted.complete(Unit)
        if (ignorePreviewCancellation) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                previewRelease.await()
            }
        }
        blockIfConfigured("preview")
        return preview
    }
    override suspend fun previewLocal(files: List<MultipartBody.Part>) = preview
    override suspend fun previewAppendBilibili(batchId: String, sourceUrl: String) = preview
    override suspend fun previewAppendLocal(batchId: String, files: List<MultipartBody.Part>) = preview
    override suspend fun batchTasks(page: Int, pageSize: Int): BatchTaskPage {
        blockIfConfigured("refresh")
        refreshError?.let { throw it }
        return BatchTaskPage(items = listOf(task("b1", "RUNNING")))
    }
    override suspend fun createBatch(request: BatchCreateRequest): BatchTask {
        blockIfConfigured("create")
        createCalls++
        createRequests += request
        return task("created", "RUNNING")
    }
    override suspend fun batchDetail(batchId: String): BatchTask {
        blockIfConfigured("detail")
        detailCalls++
        return detailSequence.removeFirstOrNull() ?: task(batchId, "DONE")
    }
    override suspend fun updateBatch(batchId: String, request: BatchUpdateRequest) = task(batchId, "RUNNING")
    override suspend fun appendBatchItems(batchId: String, request: BatchAppendRequest): BatchAppendResult {
        blockIfConfigured("append")
        appendRequests += request
        return BatchAppendResult(task = task(batchId, "RUNNING"))
    }
    override suspend fun batchItems(batchId: String) = items
    override suspend fun batchEvents(batchId: String, itemId: String) = emptyList<BatchEvent>()
    override suspend fun retryBatchItem(batchId: String, itemId: String): BatchItem {
        enterRepositoryCall()
        retryCalls += batchId to itemId
        if (retryCalls.size == 1) {
            currentCoroutineContext()[Job]?.invokeOnCompletion { firstActionCompleted.complete(Unit) }
        }
        retryStarted.complete(Unit)
        return try {
            kotlinx.coroutines.awaitCancellation()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            retryCancelled = true
            if (holdRetryCancellationCleanup) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    retryCleanupStarted.complete(Unit)
                    retryCleanupRelease.await()
                }
            }
            throw cancellation
        } finally {
            leaveRepositoryCall()
        }
    }
    override suspend fun deleteBatchItem(batchId: String, itemId: String): BatchDeleteResult {
        enterRepositoryCall()
        deleteItemCalls += batchId to itemId
        newActionObservedOldCompletion = firstActionCompleted.isCompleted
        return try {
            BatchDeleteResult(task = task(batchId, "RUNNING"), deletedCount = 1)
        } finally {
            leaveRepositoryCall()
        }
    }
    override suspend fun deleteBatchItems(batchId: String, itemIds: List<String>): BatchDeleteResult {
        deleteCalls += batchId to itemIds
        return BatchDeleteResult(task = task(batchId, "RUNNING"), deletedCount = itemIds.size)
    }
    override suspend fun exportBatch(batchId: String, itemIds: List<String>?): BatchExport {
        blockIfConfigured("export")
        return BatchExport(byteArrayOf(1), "batch.zip", "application/zip")
    }
    override suspend fun exportBatchItem(batchId: String, itemId: String) = BatchExport(byteArrayOf(1), "item.zip", "application/zip")

    private suspend fun blockIfConfigured(call: String) {
        if (blockingRepositoryCall != call) return
        blockingCallCount++
        if (blockingCallCount == 1) {
            blockingCallStarted.complete(Unit)
            if (holdFirstBlockingCallUntilRelease) {
                if (holdFirstBlockingCallNonCancellable) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        firstBlockingCallRelease.await()
                    }
                } else {
                    firstBlockingCallRelease.await()
                }
            } else {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    if (holdFirstCancellationCleanup) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            firstCancellationCleanupStarted.complete(Unit)
                            firstCancellationCleanupRelease.await()
                        }
                    }
                    throw cancellation
                }
            }
        } else if (holdSecondBlockingCall) {
            secondBlockingCallStarted.complete(Unit)
            secondBlockingCallRelease.await()
        }
    }

    private fun enterRepositoryCall() {
        activeRepositoryCalls++
        maxConcurrentRepositoryCalls = maxOf(maxConcurrentRepositoryCalls, activeRepositoryCalls)
    }

    private fun leaveRepositoryCall() {
        activeRepositoryCalls--
    }
}

private class InMemoryBatchCache(private val values: Map<String, List<BatchTask>>) : BatchCache {
    override suspend fun loadTasks(serviceBaseUrl: String): List<BatchTask> = values[serviceBaseUrl].orEmpty()
    override suspend fun saveTasks(serviceBaseUrl: String, tasks: List<BatchTask>) = Unit
}

private fun preview(vararg ids: String) = BatchPreview(items = ids.map { BatchPreviewItem(id = it, sourceUrl = "url-$it") })
private fun task(id: String, status: String) = BatchTask(id = id, status = status)
private fun previewItem(id: String) = BatchPreviewItem(id = id, sourceUrl = "url-$id")
private fun item(id: String, status: String = "FAILED") = BatchItem(id = id, batchId = "b1", status = status)
