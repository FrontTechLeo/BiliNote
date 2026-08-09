package com.bilinote.android.feature.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilinote.android.network.SyncStatus
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody

class BatchViewModel(
    private val repository: BatchRepository,
    private val cache: BatchCache? = null,
    scope: CoroutineScope? = null,
    autoRefresh: Boolean = true,
) : ViewModel() {
    private val executionScope = scope ?: viewModelScope
    private val _uiState = MutableStateFlow(BatchUiState())
    val uiState: StateFlow<BatchUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var detailJob: Job? = null
    private var actionJob: Job? = null
    private val actionJobs = mutableSetOf<Job>()
    private val actionMutex = Mutex()

    init {
        if (autoRefresh) refreshTasks()
    }

    fun refreshTasks() {
        refreshJob?.cancel()
        refreshJob = executionScope.launch {
            val currentJob = checkNotNull(currentCoroutineContext()[Job])
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            try {
                actionMutex.withLock {
                    try {
                        val page = repository.batchTasks()
                        val tasks = page.items
                        updateTasks(tasks)
                        try {
                            (cache ?: NoopBatchCache).saveTasks(repository.serviceBaseUrl, tasks)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            // Cache persistence must not turn a successful network refresh offline.
                        }
                        _uiState.update {
                            it.copy(
                                isRefreshing = false,
                                syncStatus = SyncStatus.Online,
                                errorMessage = null,
                            )
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        val cachedTasks = try {
                            (cache ?: NoopBatchCache).loadTasks(repository.serviceBaseUrl)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            emptyList()
                        }
                        if (cachedTasks.isNotEmpty()) updateTasks(cachedTasks)
                        _uiState.update {
                            it.copy(
                                isRefreshing = false,
                                syncStatus = SyncStatus.Offline,
                                errorMessage = error.message ?: error::class.simpleName ?: "刷新失败",
                            )
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                if (refreshJob === currentJob) {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    fun refresh() = refreshTasks()

    fun selectBatch(batchId: String?) {
        val normalizedId = batchId?.trim()?.takeUnless(String::isEmpty)
        if (normalizedId != uiState.value.selectedBatchId) stopDetailPolling()
        _uiState.update { state ->
            state.copy(
                selectedBatchId = normalizedId,
                selectedTask = normalizedId?.let { id -> state.tasks.firstOrNull { it.id == id } },
                items = if (normalizedId != null && state.selectedBatchId == normalizedId) state.items else emptyList(),
                selectedItemId = null,
                events = emptyList(),
                eventsItemId = null,
            )
        }
        if (normalizedId == null) stopDetailPolling()
    }

    fun select(batchId: String?) = selectBatch(batchId)

    fun loadDetail(batchId: String? = null) {
        batchId?.let(::selectBatch)
        val selectedId = batchId?.trim()?.takeUnless(String::isEmpty) ?: uiState.value.selectedBatchId
        if (selectedId == null) {
            _uiState.update { it.copy(actionError = "请选择批次") }
            return
        }
        cancelAction()
        actionJob = launchSerializedAction(
            onStart = { _uiState.update { it.copy(isLoading = true, actionError = null) } },
            onFinally = { currentJob ->
                clearActionStateIfCurrent(currentJob) { it.copy(isLoading = false) }
            },
        ) {
            try {
                val task = repository.batchDetail(selectedId)
                if (uiState.value.selectedBatchId != selectedId) return@launchSerializedAction
                val items = repository.batchItems(selectedId)
                if (uiState.value.selectedBatchId != selectedId) return@launchSerializedAction
                updateDetail(task, items)
                _uiState.update { it.copy(isLoading = false, errorMessage = null) }
                if (!task.status.isTerminal()) startDetailPolling()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, actionError = error.message ?: "加载批次详情失败")
                }
            }
        }
    }

    fun previewBilibili(sourceUrl: String) {
        runPreview { repository.previewBilibili(sourceUrl) }
    }

    fun preview(sourceUrl: String) = previewBilibili(sourceUrl)

    fun previewLocal(files: List<MultipartBody.Part>) {
        runPreview { repository.previewLocal(files).normalizedLocalPreview() }
    }

    fun previewAppendBilibili(batchId: String? = uiState.value.selectedBatchId, sourceUrl: String) {
        val id = requireBatchId(batchId) ?: return
        runPreview { repository.previewAppendBilibili(id, sourceUrl) }
    }

    fun previewAppendLocal(batchId: String? = uiState.value.selectedBatchId, files: List<MultipartBody.Part>) {
        val id = requireBatchId(batchId) ?: return
        runPreview { repository.previewAppendLocal(id, files).normalizedLocalPreview() }
    }

    fun togglePreviewSelection(itemId: String) {
        _uiState.update { state ->
            if (state.preview?.items?.withIndex()?.any { (index, item) ->
                    previewSelectionKey(item, index) == itemId
                } != true
            ) return@update state
            val next = state.selectedPreviewIds.toMutableSet()
            if (!next.add(itemId)) next.remove(itemId)
            state.copy(selectedPreviewIds = next)
        }
    }

    fun toggle(itemId: String) = togglePreviewSelection(itemId)

    fun selectAll() {
        _uiState.update { state ->
            state.copy(
                selectedPreviewIds = state.preview?.items.orEmpty().mapIndexed { index, item ->
                    previewSelectionKey(item, index)
                }.toSet(),
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPreviewIds = emptySet()) }
    }

    fun clear() = clearSelection()

    fun clearPreview() {
        cancelAction()
        _uiState.update { it.copy(preview = null, selectedPreviewIds = emptySet()) }
    }

    fun createBatch(request: BatchCreateRequest) {
        createBatch(request.title, request.sourceType, request.sourceValue, request.config, request.items)
    }

    fun createBatch(
        title: String? = null,
        sourceType: String? = null,
        sourceValue: String? = null,
        config: BatchConfig = defaultBatchConfig,
        items: List<BatchPreviewItem>? = null,
    ) {
        val selectedItems = items?.takeIf { it.isNotEmpty() } ?: selectedPreviewItems()
        if (selectedItems.isEmpty()) {
            _uiState.update { it.copy(actionError = "请选择至少一个条目") }
            return
        }
        cancelAction()
        actionJob = launchSerializedAction(
            onStart = { _uiState.update { it.copy(isCreating = true, actionError = null) } },
            onFinally = { currentJob ->
                clearActionStateIfCurrent(currentJob) { it.copy(isCreating = false) }
            },
        ) {
            try {
                val task = repository.createBatch(
                    BatchCreateRequest(
                        title = title?.trim()?.ifBlank { null },
                        sourceType = sourceType?.trim()?.ifBlank { null },
                        sourceValue = sourceValue?.trim()?.ifBlank { null },
                        config = config.normalized(),
                        items = selectedItems,
                    ),
                )
                applyTask(task)
                _uiState.update { it.copy(isCreating = false, selectedPreviewIds = emptySet()) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isCreating = false, actionError = error.message ?: "创建批次失败")
                }
            }
        }
    }

    fun updateBatch(
        batchId: String? = uiState.value.selectedBatchId,
        title: String? = null,
        config: BatchConfig? = null,
    ) {
        val id = requireBatchId(batchId) ?: return
        launchAction { state ->
            val task = repository.updateBatch(
                id,
                BatchUpdateRequest(
                    title = title?.trim()?.ifBlank { null },
                    config = config?.normalized(),
                ),
            )
            state.copy(
                tasks = state.tasks.replaceTask(task),
                selectedBatchId = task.id ?: state.selectedBatchId,
                selectedTask = task,
            )
        }
    }

    fun appendSelected(batchId: String? = uiState.value.selectedBatchId) {
        val id = requireBatchId(batchId) ?: return
        val selectedItems = selectedPreviewItems()
        if (selectedItems.isEmpty()) {
            _uiState.update { it.copy(actionError = "请选择至少一个条目") }
            return
        }
        cancelAction()
        actionJob = launchSerializedAction(
            onStart = { _uiState.update { it.copy(isAppending = true, actionError = null) } },
            onFinally = { currentJob ->
                clearActionStateIfCurrent(currentJob) { it.copy(isAppending = false) }
            },
        ) {
            try {
                val result = repository.appendBatchItems(id, BatchAppendRequest(selectedItems))
                result.task?.let(::applyTask)
                _uiState.update {
                    it.copy(
                        isAppending = false,
                        items = (it.items + result.addedItems).distinctBy { item -> item.id ?: item.sourceUrl },
                        selectedPreviewIds = emptySet(),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isAppending = false, actionError = error.message ?: "追加批次条目失败")
                }
            }
        }
    }

    fun append(batchId: String? = uiState.value.selectedBatchId) = appendSelected(batchId)

    fun retryItem(itemId: String, batchId: String? = uiState.value.selectedBatchId) {
        val id = requireBatchId(batchId) ?: return
        launchAction { state ->
            val updatedItem = repository.retryBatchItem(id, itemId)
            state.copy(items = state.items.replaceItem(updatedItem), selectedItemId = itemId)
        }
    }

    fun retry(itemId: String, batchId: String? = uiState.value.selectedBatchId) = retryItem(itemId, batchId)

    fun deleteItem(itemId: String, batchId: String? = uiState.value.selectedBatchId) {
        val id = requireBatchId(batchId) ?: return
        launchAction { state ->
            val result = repository.deleteBatchItem(id, itemId)
            state.copy(
                tasks = result.task?.let { state.tasks.replaceTask(it) } ?: state.tasks,
                selectedBatchId = result.task?.id ?: state.selectedBatchId,
                selectedTask = result.task ?: state.selectedTask,
                items = state.items.filterNot { it.id == itemId },
                selectedItemId = state.selectedItemId.takeUnless { it == itemId },
            )
        }
    }

    fun deleteItems(itemIds: List<String>, batchId: String? = uiState.value.selectedBatchId) {
        val id = requireBatchId(batchId) ?: return
        val ids = itemIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (ids.isEmpty()) {
            _uiState.update { it.copy(actionError = "请选择要删除的条目") }
            return
        }
        launchAction { state ->
            val result = repository.deleteBatchItems(id, ids)
            state.copy(
                tasks = result.task?.let { state.tasks.replaceTask(it) } ?: state.tasks,
                selectedBatchId = result.task?.id ?: state.selectedBatchId,
                selectedTask = result.task ?: state.selectedTask,
                items = state.items.filterNot { item -> item.id?.let(ids::contains) == true },
                selectedItemId = state.selectedItemId?.takeUnless(ids::contains),
                selectedPreviewIds = emptySet(),
            )
        }
    }

    fun delete(itemIds: List<String>, batchId: String? = uiState.value.selectedBatchId) = deleteItems(itemIds, batchId)

    fun loadEvents(itemId: String, batchId: String? = uiState.value.selectedBatchId) {
        val id = requireBatchId(batchId) ?: return
        launchAction { state ->
            val events = repository.batchEvents(id, itemId)
            state.copy(events = events, eventsItemId = itemId)
        }
    }

    fun exportBatch(batchId: String? = uiState.value.selectedBatchId, itemIds: List<String>? = null) {
        val id = requireBatchId(batchId) ?: return
        exportAction { repository.exportBatch(id, itemIds) }
    }

    fun export(batchId: String? = uiState.value.selectedBatchId, itemIds: List<String>? = null) =
        exportBatch(batchId, itemIds)

    fun exportItem(itemId: String, batchId: String? = uiState.value.selectedBatchId) {
        val id = requireBatchId(batchId) ?: return
        exportAction { repository.exportBatchItem(id, itemId) }
    }

    fun clearExportState() {
        _uiState.update { it.copy(exportState = BatchExportState.Idle) }
    }

    fun startDetailPolling() {
        if (detailJob?.isActive == true) return
        val batchId = uiState.value.selectedBatchId ?: return
        if (uiState.value.selectedTask?.status.isTerminal()) return
        detailJob = executionScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MILLIS)
                if (!isActive || uiState.value.selectedBatchId != batchId) return@launch
                try {
                    val (task, items) = actionMutex.withLock {
                        val task = repository.batchDetail(batchId)
                        val items = repository.batchItems(batchId)
                        task to items
                    }
                    if (!isActive || uiState.value.selectedBatchId != batchId) return@launch
                    updateDetail(task, items)
                    if (task.status.isTerminal()) return@launch
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    _uiState.update { it.copy(actionError = error.message ?: "刷新批次详情失败") }
                    return@launch
                }
            }
        }
    }

    fun stopDetailPolling() {
        detailJob?.cancel()
        detailJob = null
    }

    override fun onCleared() {
        refreshJob?.cancel()
        detailJob?.cancel()
        actionJob?.cancel()
        actionJobs.forEach { it.cancel() }
        super.onCleared()
    }

    private fun runPreview(loader: suspend () -> BatchPreview) {
        cancelAction()
        actionJob = launchSerializedAction(
            onStart = { _uiState.update { it.copy(isLoading = true, actionError = null) } },
            onFinally = { currentJob ->
                clearActionStateIfCurrent(currentJob) { it.copy(isLoading = false) }
            },
        ) {
            try {
                val result = loader()
                currentCoroutineContext().ensureActive()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        preview = result,
                        selectedPreviewIds = emptySet(),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoading = false, actionError = error.message ?: "预览失败") }
            }
        }
    }

    private fun launchAction(transform: suspend (BatchUiState) -> BatchUiState) {
        cancelAction()
        val job = launchSerializedAction {
            _uiState.update { it.copy(actionError = null) }
            try {
                val next = transform(uiState.value)
                _uiState.value = next
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update { it.copy(actionError = error.message ?: "操作失败") }
            }
        }
        actionJobs += job
        job.invokeOnCompletion { actionJobs -= job }
        actionJob = job
    }

    private fun exportAction(loader: suspend () -> BatchExport) {
        cancelAction()
        actionJob = launchSerializedAction(
            onStart = { _uiState.update { it.copy(exportState = BatchExportState.Exporting, actionError = null) } },
            onFinally = { currentJob ->
                if (actionJob === currentJob) {
                    _uiState.update { state ->
                        if (state.exportState is BatchExportState.Exporting) {
                            state.copy(exportState = BatchExportState.Idle)
                        } else {
                            state
                        }
                    }
                }
            },
        ) {
            try {
                val exported = withContext(Dispatchers.IO) { loader() }
                _uiState.update { it.copy(exportState = BatchExportState.Ready(exported)) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(exportState = BatchExportState.Error(error.message ?: "导出失败"))
                }
            }
        }
    }

    private fun clearActionStateIfCurrent(currentJob: Job, transform: (BatchUiState) -> BatchUiState) {
        if (actionJob === currentJob) _uiState.update(transform)
    }

    private fun cancelAction() {
        actionJob?.cancel()
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                isCreating = false,
                isAppending = false,
                exportState = if (state.exportState is BatchExportState.Exporting) {
                    BatchExportState.Idle
                } else {
                    state.exportState
                },
            )
        }
    }

    private fun launchSerializedAction(
        previousJob: Job? = actionJob,
        onStart: () -> Unit = {},
        onFinally: (Job) -> Unit = {},
        block: suspend () -> Unit,
    ): Job =
        executionScope.launch {
            val currentJob = checkNotNull(currentCoroutineContext()[Job])
            if (previousJob != null && previousJob !== currentJob) {
                withContext(kotlinx.coroutines.NonCancellable) { previousJob.join() }
                currentCoroutineContext().ensureActive()
            }
            onStart()
            try {
                actionMutex.withLock { block() }
            } finally {
                onFinally(checkNotNull(currentCoroutineContext()[Job]))
            }
        }

    private fun selectedPreviewItems(): List<BatchPreviewItem> =
        uiState.value.preview?.items.orEmpty().mapIndexedNotNull { index, item ->
            item.takeIf { previewSelectionKey(it, index) in uiState.value.selectedPreviewIds }
        }

    fun previewSelectionKey(item: BatchPreviewItem, index: Int): String {
        val base = item.id?.trim()?.takeIf(String::isNotEmpty)
            ?: item.sourceUrl?.trim()?.takeIf(String::isNotEmpty)
            ?: item.title?.trim()?.takeIf(String::isNotEmpty)
            ?: "preview"
        return "$base-$index"
    }

    private fun BatchPreview.normalizedLocalPreview(): BatchPreview {
        val uniqueItems = filterDuplicatePreviewItems(items)
        return copy(
            items = naturalSortBy(uniqueItems) { item ->
                item.position?.toString() ?: item.sourceUrl ?: item.title ?: "~"
            },
            duplicateCount = duplicateCount + items.size - uniqueItems.size,
        )
    }

    private fun requireBatchId(batchId: String?): String? =
        batchId?.trim()?.takeUnless(String::isEmpty) ?: run {
            _uiState.update { it.copy(actionError = "请选择批次") }
            null
        }

    private fun updateTasks(tasks: List<BatchTask>) {
        _uiState.update { state ->
            val selectedId = state.selectedBatchId
            state.copy(
                tasks = tasks,
                selectedTask = selectedId?.let { id -> tasks.firstOrNull { it.id == id } } ?: state.selectedTask,
            )
        }
    }

    private fun updateDetail(task: BatchTask, items: List<BatchItem>) {
        _uiState.update { state ->
            val id = task.id ?: state.selectedBatchId
            val nextTasks = if (task.id == null) state.tasks else state.tasks.replaceTask(task)
            state.copy(
                selectedBatchId = id,
                selectedTask = task,
                tasks = nextTasks,
                items = items,
                selectedItemId = state.selectedItemId?.takeIf { itemId -> items.any { it.id == itemId } },
            )
        }
    }

    private fun applyTask(task: BatchTask) {
        _uiState.update { state ->
            state.copy(
                tasks = state.tasks.replaceTask(task),
                selectedBatchId = task.id ?: state.selectedBatchId,
                selectedTask = task,
            )
        }
    }

    private fun List<BatchItem>.replaceItem(item: BatchItem): List<BatchItem> =
        if (item.id == null) this else map { existing -> if (existing.id == item.id) item else existing }

    private fun List<BatchTask>.replaceTask(task: BatchTask): List<BatchTask> =
        if (task.id == null) this else if (any { it.id == task.id }) map { existing -> if (existing.id == task.id) task else existing } else this + task

    private fun String?.isTerminal(): Boolean =
        this?.trim()?.uppercase(Locale.ROOT)?.let(TERMINAL_STATUSES::contains) == true

    private companion object {
        const val POLL_INTERVAL_MILLIS = 3_500L
        val TERMINAL_STATUSES = setOf("SUCCESS", "FAILED", "PARTIAL_FAILED", "CANCELLED", "DONE")
    }
}
