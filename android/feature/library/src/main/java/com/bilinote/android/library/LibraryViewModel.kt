package com.bilinote.android.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilinote.android.database.entity.TaskEntity
import com.bilinote.android.home.data.TaskRepository
import com.bilinote.android.network.RepositoryRefreshResult
import com.bilinote.android.network.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface LibraryTaskDataSource {
    fun observeTasks(): Flow<List<TaskEntity>>

    suspend fun refresh(): RepositoryRefreshResult<List<TaskEntity>>
}

class TaskRepositoryLibraryTaskDataSource(
    private val repository: TaskRepository,
) : LibraryTaskDataSource {
    override fun observeTasks(): Flow<List<TaskEntity>> = repository.recent()

    override suspend fun refresh(): RepositoryRefreshResult<List<TaskEntity>> = repository.refresh()
}

enum class LibraryTaskAvailability {
    Success,
    Failed,
    Processing,
}

private val SUCCESS_STATUSES = setOf("SUCCESS", "COMPLETED", "DONE")
private val FAILED_STATUSES = setOf("FAILED", "FAILURE", "ERROR", "CANCELLED")

data class LibraryTask(
    val taskId: String,
    val title: String,
    val status: String,
    val message: String?,
    val platform: String,
    val sourceUrl: String,
    val hasResult: Boolean,
    val availability: LibraryTaskAvailability,
) {
    val canOpenReader: Boolean
        get() = availability == LibraryTaskAvailability.Success && hasResult

    val canExport: Boolean
        get() = canOpenReader

    val canRegenerate: Boolean
        get() = availability == LibraryTaskAvailability.Failed

    companion object {
        fun from(entity: TaskEntity): LibraryTask {
            val normalizedStatus = entity.status.trim().uppercase()
            val availability = when {
                normalizedStatus in SUCCESS_STATUSES -> LibraryTaskAvailability.Success
                normalizedStatus in FAILED_STATUSES -> LibraryTaskAvailability.Failed
                else -> LibraryTaskAvailability.Processing
            }
            return LibraryTask(
                taskId = entity.taskId,
                title = entity.title,
                status = entity.status,
                message = entity.message,
                platform = entity.platform,
                sourceUrl = entity.sourceUrl,
                hasResult = entity.hasResult,
                availability = availability,
            )
        }
    }
}

data class LibraryUiState(
    val tasks: List<LibraryTask> = emptyList(),
    val selectedTaskId: String? = null,
    val isRefreshing: Boolean = false,
    val syncStatus: SyncStatus? = null,
    val errorMessage: String? = null,
)

class LibraryViewModel(
    private val dataSource: LibraryTaskDataSource,
    scope: CoroutineScope? = null,
    autoRefresh: Boolean = true,
) : ViewModel() {
    private val childScope = scope?.let { externalScope ->
        val parentJob = externalScope.coroutineContext[Job]
        CoroutineScope(externalScope.coroutineContext + SupervisorJob(parentJob))
    }
    private val executionScope = childScope ?: viewModelScope
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    init {
        executionScope.launch {
            dataSource.observeTasks().collect { entities ->
                updateTasks(entities)
            }
        }
        if (autoRefresh) refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = executionScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            try {
                val result = dataSource.refresh()
                updateTasks(result.data)
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        syncStatus = result.status,
                        errorMessage = result.errorMessage,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        syncStatus = SyncStatus.Offline,
                        errorMessage = error.message ?: error::class.simpleName ?: "Refresh failed",
                    )
                }
            }
        }
    }

    fun selectTask(taskId: String?) {
        _uiState.update { state ->
            state.copy(selectedTaskId = taskId?.takeIf { candidate -> state.tasks.any { it.taskId == candidate } })
        }
    }

    private fun updateTasks(entities: List<TaskEntity>) {
        val tasks = entities
            .take(MAX_TASKS)
            .map(LibraryTask::from)
        _uiState.update { state ->
            state.copy(
                tasks = tasks,
                selectedTaskId = state.selectedTaskId?.takeIf { id -> tasks.any { it.taskId == id } },
            )
        }
    }

    override fun onCleared() {
        childScope?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_TASKS = 50
    }
}
