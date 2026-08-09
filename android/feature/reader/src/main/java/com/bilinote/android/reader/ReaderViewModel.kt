package com.bilinote.android.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilinote.android.database.dao.TaskDao
import com.bilinote.android.database.entity.ReadingProgressEntity
import com.bilinote.android.database.entity.TaskEntity
import com.bilinote.android.network.BiliNoteApi
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface ReaderDataSource {
    suspend fun loadTask(taskId: String, serviceBaseUrl: String): TaskEntity?

    suspend fun refreshTask(task: TaskEntity): TaskEntity

    suspend fun export(taskId: String, serviceBaseUrl: String): ReaderExport
}

data class ReaderExport(
    val body: ByteArray,
    val fileName: String,
    val contentType: String?,
)

class BiliNoteReaderDataSource(
    private val api: BiliNoteApi,
    private val taskDao: TaskDao,
) : ReaderDataSource {
    override suspend fun loadTask(taskId: String, serviceBaseUrl: String): TaskEntity? =
        taskDao.observeTask(serviceBaseUrl, taskId).first()

    override suspend fun refreshTask(task: TaskEntity): TaskEntity {
        val status = api.taskStatus(task.taskId)
        val markdown = status.result?.markdown ?: task.markdown
        val updated = task.copy(
            status = status.status?.trim().takeUnless { it.isNullOrEmpty() } ?: task.status,
            message = status.message ?: task.message,
            platform = status.platform?.trim().takeUnless { it.isNullOrEmpty() } ?: task.platform,
            sourceUrl = status.sourceUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: task.sourceUrl,
            hasResult = task.hasResult || !markdown.isNullOrBlank(),
            updatedAt = System.currentTimeMillis(),
            markdown = markdown,
        )
        taskDao.upsert(updated)
        return updated
    }

    override suspend fun export(taskId: String, serviceBaseUrl: String): ReaderExport {
        val response = api.exportNote(taskId)
        val body = response.body() ?: throw IllegalStateException("导出响应为空")
        return ReaderExport(
            body = body.bytes(),
            fileName = response.headers().fileNameOrDefault("note-$taskId.md"),
            contentType = body.contentType()?.toString(),
        )
    }
}

private fun okhttp3.Headers.fileNameOrDefault(default: String): String {
    val header = get("Content-Disposition").orEmpty()
    val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.getOrNull(1)
    val plain = Regex("filename=\\\"?([^;\\\"]+)", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.getOrNull(1)
    return (encoded?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: plain)
        ?.trim()
        ?.takeUnless { it.isEmpty() }
        ?: default
}

sealed interface ReaderLoadState {
    data object Loading : ReaderLoadState

    data class Success(val task: TaskEntity) : ReaderLoadState

    data class Error(val message: String) : ReaderLoadState
}

sealed interface ReaderExportState {
    data object Idle : ReaderExportState

    data object Exporting : ReaderExportState

    data class Ready(val export: ReaderExport) : ReaderExportState

    data class Error(val message: String) : ReaderExportState
}

data class ReaderUiState(
    val loadState: ReaderLoadState = ReaderLoadState.Loading,
    val exportState: ReaderExportState = ReaderExportState.Idle,
)

class ReaderViewModel(
    private val taskId: String,
    private val serviceBaseUrl: String,
    private val dataSource: ReaderDataSource,
    private val progressStore: ProgressStore,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val childJob: CompletableJob? = scope?.let { SupervisorJob(it.coroutineContext[Job]) }
    private val executionScope = scope?.let { CoroutineScope(it.coroutineContext + childJob!!) } ?: viewModelScope
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
    private val _progress = MutableStateFlow<ReadingProgressEntity?>(null)
    val progress: StateFlow<ReadingProgressEntity?> = _progress.asStateFlow()
    internal val progressStoreForUi: ProgressStore get() = progressStore
    private var loadJob: Job? = null
    private var exportJob: Job? = null

    init {
        executionScope.launch {
            progressStore.observe(taskId, serviceBaseUrl).collect { _progress.value = it }
        }
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = executionScope.launch {
            _uiState.update { it.copy(loadState = ReaderLoadState.Loading) }
            try {
                val cached = dataSource.loadTask(taskId, serviceBaseUrl) ?: placeholderTask()
                val task = if (!cached.markdown.isNullOrBlank()) cached else dataSource.refreshTask(cached)
                val markdown = task.markdown?.trim().orEmpty()
                if (markdown.isEmpty()) {
                    val status = task.status.trim().uppercase()
                    val message = when (status) {
                        "FAILED", "ERROR", "CANCELLED" -> task.message ?: "任务处理失败"
                        else -> task.message ?: "笔记仍在处理中，请稍后重试"
                    }
                    _uiState.update { it.copy(loadState = ReaderLoadState.Error(message)) }
                } else {
                    _uiState.update { it.copy(loadState = ReaderLoadState.Success(task)) }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(loadState = ReaderLoadState.Error(exception.message ?: "加载笔记失败"))
                }
            }
        }
    }

    fun export() {
        exportJob?.cancel()
        exportJob = executionScope.launch {
            val task = (uiState.value.loadState as? ReaderLoadState.Success)?.task ?: return@launch
            _uiState.update { it.copy(exportState = ReaderExportState.Exporting) }
            try {
                val exported = withContext(Dispatchers.IO) {
                    dataSource.export(task.taskId, serviceBaseUrl)
                }
                _uiState.update { it.copy(exportState = ReaderExportState.Ready(exported)) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(exportState = ReaderExportState.Error(exception.message ?: "导出失败"))
                }
            }
        }
    }

    fun clearExportState() {
        _uiState.update { it.copy(exportState = ReaderExportState.Idle) }
    }

    override fun onCleared() {
        loadJob?.cancel()
        exportJob?.cancel()
        childJob?.cancel()
        super.onCleared()
    }

    private fun placeholderTask() = TaskEntity(
        serviceBaseUrl = serviceBaseUrl,
        taskId = taskId,
        title = taskId,
        status = "PENDING",
        message = null,
        platform = "unknown",
        sourceUrl = "",
        hasResult = false,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        markdown = null,
    )
}
