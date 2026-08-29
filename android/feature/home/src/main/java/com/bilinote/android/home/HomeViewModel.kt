package com.bilinote.android.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilinote.android.core.designsystem.ImageCompressionLevel
import com.bilinote.android.home.data.TaskRepository
import com.bilinote.android.network.BiliNoteApi
import com.bilinote.android.network.dto.GenerateNoteRequest
import com.bilinote.android.network.dto.GenerateNoteResponse
import com.bilinote.android.network.dto.SingleTaskStatusDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MultipartBody
import kotlin.coroutines.coroutineContext

interface HomeRepository {
    fun observeActiveTasks(): Flow<List<HomeTask>>

    fun observeRecentTasks(): Flow<List<HomeTask>>

    suspend fun generateNote(request: GenerateNoteRequest): GenerateNoteResponse

    suspend fun taskStatus(taskId: String): SingleTaskStatusDto
}

class BiliNoteHomeRepository(
    private val api: BiliNoteApi,
    private val taskRepository: TaskRepository,
) : HomeRepository {
    override fun observeActiveTasks(): Flow<List<HomeTask>> = taskRepository.active().map { tasks ->
        tasks.map { task ->
            HomeTask(
                taskId = task.taskId,
                title = task.title,
                status = task.status,
                message = task.message,
                platform = task.platform,
                sourceUrl = task.sourceUrl,
                hasResult = task.hasResult,
            )
        }
    }

    override fun observeRecentTasks(): Flow<List<HomeTask>> = taskRepository.recent().map { tasks ->
        tasks.map { task ->
            HomeTask(
                taskId = task.taskId,
                title = task.title,
                status = task.status,
                message = task.message,
                platform = task.platform,
                sourceUrl = task.sourceUrl,
                hasResult = task.hasResult,
            )
        }
    }

    override suspend fun generateNote(request: GenerateNoteRequest): GenerateNoteResponse {
        val response = api.generateNote(request)
        response.taskId?.trim()?.takeUnless { it.isNullOrEmpty() }?.let { taskId ->
            taskRepository.upsertGeneratedTask(taskId, request, response)
        }
        return response
    }

    override suspend fun taskStatus(taskId: String): SingleTaskStatusDto {
        val status = api.taskStatus(taskId)
        taskRepository.updateTaskStatus(status)
        return status
    }
}

fun interface LocalVideoUploader {
    suspend fun upload(video: MultipartBody.Part, onProgress: (Int) -> Unit): String
}

class BiliNoteLocalVideoUploader(private val api: BiliNoteApi) : LocalVideoUploader {
    override suspend fun upload(video: MultipartBody.Part, onProgress: (Int) -> Unit): String {
        onProgress(0)
        val url = api.upload(video).url?.trim().orEmpty()
        if (url.isEmpty()) throw IllegalStateException("上传成功但服务端未返回视频地址")
        onProgress(100)
        return url
    }
}

fun interface HomeClock {
    suspend fun delay(millis: Long)
}

private val defaultHomeClock = HomeClock { millis -> delay(millis) }

data class HomeNoteDraft(
    val videoUrl: String,
    val platform: String,
    val providerId: String,
    val modelName: String,
    val quality: String,
    val style: String? = "tutorial",
    val format: List<String> = listOf("toc", "summary"),
    val extras: String? = null,
    val videoUnderstanding: Boolean = false,
    val videoInterval: Int = 6,
    val gridSize: List<Int> = listOf(2, 2),
    val imageCompression: String = ImageCompressionLevel.HIGH.wireValue,
    val localVideo: MultipartBody.Part? = null,
) {
    fun toGenerateNoteRequest(videoUrl: String = this.videoUrl): GenerateNoteRequest {
        // Backend invariant: screenshot is enabled only by format=screenshot plus video understanding.
        val normalizedFormat = format
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .filter { it != "screenshot" || videoUnderstanding }
        return GenerateNoteRequest(
            videoUrl = videoUrl.trim(),
            platform = platform.trim(),
            providerId = providerId.trim(),
            modelName = modelName.trim(),
            quality = quality.trim(),
            style = style?.trim()?.ifBlank { null },
            format = normalizedFormat,
            link = normalizedFormat.contains("link"),
            screenshot = normalizedFormat.contains("screenshot"),
            extras = extras?.trim()?.ifBlank { null },
            videoUnderstanding = videoUnderstanding,
            videoInterval = videoInterval,
            gridSize = gridSize,
            imageCompression = ImageCompressionLevel.fromWireValue(imageCompression).wireValue,
        )
    }
}

class HomeViewModel(
    private val repository: HomeRepository,
    private val uploader: LocalVideoUploader? = null,
    private val clock: HomeClock = defaultHomeClock,
    private val scope: CoroutineScope? = null,
) : ViewModel() {
    private val executionScope = scope ?: viewModelScope
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _navigationEvents = Channel<ReaderNavigation>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<ReaderNavigation> = _navigationEvents.receiveAsFlow()

    private var submitJob: Job? = null
    private var pollingJob: Job? = null
    private var currentTaskId: String? = null
    private var currentTaskGeneration: Long? = null
    private var submissionId = 0L
    private var readerNavigationSent = false
    private var screenStarted = false

    init {
        executionScope.launch {
            repository.observeActiveTasks().collect { tasks ->
                _uiState.update { it.copy(activeTasks = tasks) }
            }
        }
        executionScope.launch {
            repository.observeRecentTasks().collect { tasks ->
                _uiState.update { it.copy(recentTasks = tasks) }
            }
        }
    }

    fun submit(draft: HomeNoteDraft) {
        submitJob?.cancel()
        pollingJob?.cancel()
        pollingJob = null
        currentTaskId = null
        currentTaskGeneration = null
        readerNavigationSent = false
        val requestId = ++submissionId
        submitJob = executionScope.launch {
            try {
                validateDraft(draft)
                val submissionJob = coroutineContext[Job]
                val videoUrl = if (draft.localVideo != null) {
                    val localUploader = uploader ?: throw IllegalStateException("未配置本地视频上传器")
                    if (requestId == submissionId && coroutineContext.isActive) {
                        updateWorkflow(HomeWorkflowState.Uploading(0))
                    }
                    localUploader.upload(draft.localVideo) { percent ->
                        if (requestId == submissionId && submissionJob?.isActive == true) {
                            updateWorkflow(HomeWorkflowState.Uploading(percent.coerceIn(0, 100)))
                        }
                    }.trim()
                } else {
                    draft.videoUrl.trim()
                }
                coroutineContext.ensureActive()
                if (requestId != submissionId) return@launch
                if (videoUrl.isEmpty()) throw IllegalArgumentException("请输入视频链接或选择本地视频")
                if (draft.providerId.isBlank() || draft.modelName.isBlank()) {
                    throw IllegalArgumentException("请先选择用于生成笔记的 AI 模型")
                }

                coroutineContext.ensureActive()
                if (requestId != submissionId) return@launch
                updateWorkflow(HomeWorkflowState.Submitting)
                val response = repository.generateNote(draft.toGenerateNoteRequest(videoUrl))
                val taskId = response.taskId?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: throw IllegalStateException("服务端未返回任务 ID")
                if (requestId != submissionId) return@launch
                currentTaskId = taskId
                currentTaskGeneration = requestId
                updateWorkflow(HomeWorkflowState.Polling(taskId))
                startPollingIfNeeded()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (requestId == submissionId) {
                    updateWorkflow(HomeWorkflowState.Failure(exception.message ?: "创建任务失败"))
                }
            }
        }
    }

    fun retryPolling() {
        val taskId = currentTaskId ?: return
        if (currentTaskGeneration == null) return
        if (uiState.value.workflow is HomeWorkflowState.Success) return
        updateWorkflow(HomeWorkflowState.Polling(taskId))
        startPollingIfNeeded()
    }

    fun onScreenStarted() {
        screenStarted = true
        if (uiState.value.workflow is HomeWorkflowState.Polling) startPollingIfNeeded()
    }

    fun onScreenStopped() {
        screenStarted = false
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun startPollingIfNeeded() {
        if (!screenStarted || pollingJob?.isActive == true) return
        val taskId = currentTaskId ?: return
        val generation = currentTaskGeneration ?: return
        if (uiState.value.workflow is HomeWorkflowState.Success) return
        pollingJob = executionScope.launch {
            try {
                pollUntilTerminal(taskId, generation)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (isCurrentPolling(taskId, generation)) {
                    updateWorkflow(HomeWorkflowState.Failure(exception.message ?: "查询任务状态失败"))
                }
            }
        }
    }

    private suspend fun pollUntilTerminal(taskId: String, generation: Long) {
        while (coroutineContext.isActive && isCurrentPolling(taskId, generation)) {
            val status = repository.taskStatus(taskId)
            coroutineContext.ensureActive()
            if (!isCurrentPolling(taskId, generation)) return
            when (status.status.orEmpty().trim().uppercase()) {
                "SUCCESS", "COMPLETED", "DONE" -> {
                    coroutineContext.ensureActive()
                    if (!isCurrentPolling(taskId, generation)) return
                    updateWorkflow(HomeWorkflowState.Success(taskId, status.result?.markdown))
                    if (!readerNavigationSent) {
                        coroutineContext.ensureActive()
                        if (!isCurrentPolling(taskId, generation)) return
                        readerNavigationSent = true
                        _navigationEvents.send(ReaderNavigation(taskId))
                    }
                    return
                }

                "FAILED", "ERROR", "CANCELLED" -> {
                    coroutineContext.ensureActive()
                    if (!isCurrentPolling(taskId, generation)) return
                    updateWorkflow(HomeWorkflowState.Failure(status.message ?: "任务处理失败"))
                    return
                }

                else -> {
                    coroutineContext.ensureActive()
                    if (!isCurrentPolling(taskId, generation)) return
                    updateWorkflow(HomeWorkflowState.Polling(taskId, status.status))
                    clock.delay(POLL_INTERVAL_MILLIS)
                    coroutineContext.ensureActive()
                    if (!isCurrentPolling(taskId, generation)) return
                }
            }
        }
    }

    private fun validateDraft(draft: HomeNoteDraft) {
        if (draft.providerId.isBlank() || draft.modelName.isBlank()) {
            throw IllegalArgumentException("请先选择用于生成笔记的 AI 模型")
        }
        if (draft.localVideo == null && draft.videoUrl.isBlank()) {
            throw IllegalArgumentException("请输入视频链接或选择本地视频")
        }
    }

    private fun isCurrentPolling(taskId: String, generation: Long): Boolean =
        screenStarted && currentTaskId == taskId && currentTaskGeneration == generation

    private fun updateWorkflow(workflow: HomeWorkflowState) {
        _uiState.update { it.copy(workflow = workflow) }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2_500L
    }

    override fun onCleared() {
        submitJob?.cancel()
        pollingJob?.cancel()
        if (scope != null) scope.cancel()
        _navigationEvents.close()
        super.onCleared()
    }
}
