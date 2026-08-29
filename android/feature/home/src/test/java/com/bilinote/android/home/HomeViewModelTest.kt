package com.bilinote.android.home

import com.bilinote.android.network.dto.GenerateNoteResponse
import com.bilinote.android.network.dto.SingleTaskStatusDto
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val testScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        testScopes.forEach { it.cancel() }
        testScopes.clear()
    }

    @Test
    fun screenshotFormatRequiresVideoUnderstanding() = runTest {
        val repository = FakeHomeRepository()
        val viewModel = HomeViewModel(repository = repository, scope = createViewModelScope())

        viewModel.submit(
            HomeNoteDraft(
                videoUrl = "https://example.com/video",
                platform = "bilibili",
                providerId = "provider-1",
                modelName = "model-1",
                quality = "medium",
                style = "tutorial",
                format = listOf("toc", "link", "screenshot", "summary"),
                videoUnderstanding = false,
                videoInterval = 6,
                gridSize = listOf(2, 2),
            ),
        )
        advanceUntilIdle()

        val request = repository.generatedRequests.single()
        assertEquals("https://example.com/video", request.videoUrl)
        assertEquals("bilibili", request.platform)
        assertEquals("provider-1", request.providerId)
        assertEquals("model-1", request.modelName)
        assertEquals("medium", request.quality)
        assertEquals("tutorial", request.style)
        assertEquals(listOf("toc", "link", "summary"), request.format)
        assertTrue(request.link == true)
        assertFalse(request.screenshot == true)
        assertEquals(null, request.extras)
        assertEquals(false, request.videoUnderstanding)
        assertEquals(6, request.videoInterval)
        assertEquals(listOf(2, 2), request.gridSize)
        assertEquals("high", request.imageCompression)
    }

    @Test
    fun imageCompressionIsForwardedWithGenerateRequest() = runTest {
        val repository = FakeHomeRepository()
        val viewModel = HomeViewModel(repository = repository, scope = createViewModelScope())

        viewModel.submit(defaultDraft().copy(imageCompression = "original"))
        advanceUntilIdle()

        assertEquals("original", repository.generatedRequests.single().imageCompression)
    }

    @Test
    fun videoUnderstandingWithoutScreenshotFormatKeepsScreenshotDisabled() = runTest {
        val repository = FakeHomeRepository()
        val viewModel = HomeViewModel(repository = repository, scope = createViewModelScope())

        viewModel.submit(
            defaultDraft().copy(
                format = listOf("toc", "summary"),
                videoUnderstanding = true,
            ),
        )
        advanceUntilIdle()

        val request = repository.generatedRequests.single()
        // video understanding is independent; screenshot requires both the format flag and understanding.
        assertEquals(listOf("toc", "summary"), request.format)
        assertFalse(request.screenshot == true)
        assertEquals(true, request.videoUnderstanding)
    }

    @Test
    fun localInputIsValidatedBeforeUpload() = runTest {
        val repository = FakeHomeRepository()
        val uploader = FakeLocalVideoUploader()
        val viewModel = HomeViewModel(repository = repository, uploader = uploader, scope = createViewModelScope())

        viewModel.submit(
            HomeNoteDraft(
                videoUrl = "",
                platform = "local",
                providerId = "",
                modelName = "",
                quality = "medium",
                localVideo = testVideoPart(),
            ),
        )
        advanceUntilIdle()

        assertEquals(HomeWorkflowState.Failure("请先选择用于生成笔记的 AI 模型"), viewModel.uiState.value.workflow)
        assertTrue(uploader.progressUpdates.isEmpty())
        assertTrue(repository.generatedRequests.isEmpty())
    }

    @Test
    fun localVideoIsUploadedBeforeGenerateNote() = runTest {
        val order = mutableListOf<String>()
        val repository = FakeHomeRepository(onGenerate = { order += "generate" })
        val uploader = FakeLocalVideoUploader(onUpload = { order += "upload" })
        val viewModel = HomeViewModel(repository = repository, uploader = uploader, scope = createViewModelScope())

        viewModel.submit(
            HomeNoteDraft(
                videoUrl = "",
                platform = "local",
                providerId = "provider-1",
                modelName = "model-1",
                quality = "medium",
                localVideo = testVideoPart(),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("upload", "generate"), order)
        assertEquals("https://example.com/uploaded.mp4", repository.generatedRequests.single().videoUrl)
        assertEquals(2, uploader.progressUpdates.size)
        assertEquals(0, uploader.progressUpdates.first())
        assertEquals(100, uploader.progressUpdates.last())
    }

    @Test
    fun oldNonCooperativeUploadCannotUpdateOrSubmitAfterNewSubmission() = runTest {
        val uploadGate = CompletableDeferred<Unit>()
        val uploader = NonCooperativeUploader(uploadGate)
        val repository = FakeHomeRepository()
        val viewModel = HomeViewModel(repository = repository, uploader = uploader, scope = createViewModelScope())

        viewModel.submit(localDraft())
        runCurrent()
        assertEquals(HomeWorkflowState.Uploading(25), viewModel.uiState.value.workflow)

        viewModel.submit(defaultDraft().copy(videoUrl = "https://example.com/new"))
        advanceUntilIdle()
        assertEquals(HomeWorkflowState.Polling("task-1"), viewModel.uiState.value.workflow)
        assertEquals(listOf("https://example.com/new"), repository.generatedRequests.map { it.videoUrl })

        uploadGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(HomeWorkflowState.Polling("task-1"), viewModel.uiState.value.workflow)
        assertEquals(listOf("https://example.com/new"), repository.generatedRequests.map { it.videoUrl })
    }

    @Test
    fun failedPollingCanBeRetried() = runTest {
        val repository = FakeHomeRepository(
            statuses = ArrayDeque(
                listOf(
                    Result.failure(IOException("temporary polling failure")),
                    Result.success(SingleTaskStatusDto(taskId = "task-1", status = "SUCCESS")),
                ),
            ),
        )
        val viewModel = HomeViewModel(repository = repository, scope = createViewModelScope())
        viewModel.onScreenStarted()

        viewModel.submit(defaultDraft())
        advanceUntilIdle()
        assertEquals(HomeWorkflowState.Failure("temporary polling failure"), viewModel.uiState.value.workflow)

        viewModel.retryPolling()
        advanceUntilIdle()
        assertEquals(HomeWorkflowState.Success("task-1"), viewModel.uiState.value.workflow)
    }

    @Test
    fun successfulCompletionIsBufferedUntilNavigationIsCollected() = runTest {
        val viewModel = HomeViewModel(repository = FakeHomeRepository(), scope = createViewModelScope())
        viewModel.onScreenStarted()

        viewModel.submit(defaultDraft())
        advanceUntilIdle()

        val navigation = withTimeout(1_000) { viewModel.navigationEvents.first() }
        assertEquals(ReaderNavigation("task-1"), navigation)
    }

    @Test
    fun nonTerminalPollingWaits2500MillisBeforeNextRequest() = runTest {
        val repository = FakeHomeRepository(
            statuses = ArrayDeque(
                listOf(
                    Result.success(SingleTaskStatusDto(taskId = "task-1", status = "RUNNING")),
                    Result.success(SingleTaskStatusDto(taskId = "task-1", status = "SUCCESS")),
                ),
            ),
        )
        val delays = mutableListOf<Long>()
        val viewModel = HomeViewModel(
            repository = repository,
            clock = HomeClock { millis -> delays += millis },
            scope = createViewModelScope(),
        )
        viewModel.onScreenStarted()

        viewModel.submit(defaultDraft())
        advanceUntilIdle()

        assertEquals(listOf(2_500L), delays)
        assertEquals(HomeWorkflowState.Success("task-1"), viewModel.uiState.value.workflow)
    }

    @Test
    fun stoppedNonCooperativePollingResultCannotOverwriteState() = runTest {
        val result = CompletableDeferred<SingleTaskStatusDto>()
        val viewModel = HomeViewModel(
            repository = NonCooperativeHomeRepository(result),
            scope = createViewModelScope(),
        )
        viewModel.onScreenStarted()

        viewModel.submit(defaultDraft())
        runCurrent()
        assertEquals(HomeWorkflowState.Polling("task-1"), viewModel.uiState.value.workflow)

        viewModel.onScreenStopped()
        result.complete(SingleTaskStatusDto(taskId = "task-1", status = "SUCCESS"))
        advanceUntilIdle()

        assertEquals(HomeWorkflowState.Polling("task-1"), viewModel.uiState.value.workflow)
    }

    @Test
    fun oldNonCooperativePollingResultCannotOverwriteNewSubmission() = runTest {
        val oldResult = CompletableDeferred<SingleTaskStatusDto>()
        val repository = MultiSubmissionRepository(oldResult)
        val viewModel = HomeViewModel(repository = repository, scope = createViewModelScope())
        viewModel.onScreenStarted()

        viewModel.submit(defaultDraft())
        runCurrent()
        viewModel.submit(defaultDraft().copy(videoUrl = "https://example.com/second"))
        advanceUntilIdle()
        assertEquals(HomeWorkflowState.Success("task-2"), viewModel.uiState.value.workflow)

        oldResult.complete(SingleTaskStatusDto(taskId = "task-1", status = "SUCCESS"))
        advanceUntilIdle()

        assertEquals(HomeWorkflowState.Success("task-2"), viewModel.uiState.value.workflow)
    }

    @Test
    fun successEmitsReaderNavigationExactlyOnce() = runTest {
        val viewModel = HomeViewModel(repository = FakeHomeRepository(), scope = createViewModelScope())
        val navigations = mutableListOf<ReaderNavigation>()
        val collectJob = createViewModelScope().launch {
            viewModel.navigationEvents.collect { navigations += it }
        }
        viewModel.onScreenStarted()

        viewModel.submit(defaultDraft())
        advanceUntilIdle()
        viewModel.onScreenStopped()
        viewModel.onScreenStarted()
        advanceUntilIdle()

        collectJob.cancel()
        assertEquals(listOf(ReaderNavigation("task-1")), navigations)
    }

    private fun TestScope.createViewModelScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { testScopes += it }

    private fun defaultDraft() = HomeNoteDraft(
        videoUrl = "https://example.com/video",
        platform = "bilibili",
        providerId = "provider-1",
        modelName = "model-1",
        quality = "medium",
    )

    private fun localDraft() = HomeNoteDraft(
        videoUrl = "",
        platform = "local",
        providerId = "provider-1",
        modelName = "model-1",
        quality = "medium",
        localVideo = testVideoPart(),
    )

    private fun testVideoPart(): MultipartBody.Part = MultipartBody.Part.createFormData(
        "file",
        "video.mp4",
        "video".toRequestBody("video/mp4".toMediaType()),
    )
}

private class FakeHomeRepository(
    private val statuses: ArrayDeque<Result<SingleTaskStatusDto>> = ArrayDeque(
        listOf(Result.success(SingleTaskStatusDto(taskId = "task-1", status = "SUCCESS"))),
    ),
    private val onGenerate: () -> Unit = {},
) : HomeRepository {
    val generatedRequests = mutableListOf<com.bilinote.android.network.dto.GenerateNoteRequest>()
    private val active = MutableStateFlow<List<HomeTask>>(emptyList())
    private val recent = MutableStateFlow<List<HomeTask>>(emptyList())

    override fun observeActiveTasks(): Flow<List<HomeTask>> = active

    override fun observeRecentTasks(): Flow<List<HomeTask>> = recent

    override suspend fun generateNote(request: com.bilinote.android.network.dto.GenerateNoteRequest): GenerateNoteResponse {
        onGenerate()
        generatedRequests += request
        return GenerateNoteResponse(taskId = "task-1", status = "PENDING")
    }

    override suspend fun taskStatus(taskId: String): SingleTaskStatusDto = statuses.removeFirst().getOrThrow()
}

private class FakeLocalVideoUploader(
    private val onUpload: () -> Unit = {},
) : LocalVideoUploader {
    val progressUpdates = mutableListOf<Int>()

    override suspend fun upload(video: MultipartBody.Part, onProgress: (Int) -> Unit): String {
        onUpload()
        onProgress(0)
        progressUpdates += 0
        onProgress(100)
        progressUpdates += 100
        return "https://example.com/uploaded.mp4"
    }
}

private class NonCooperativeUploader(
    private val gate: CompletableDeferred<Unit>,
) : LocalVideoUploader {
    override suspend fun upload(video: MultipartBody.Part, onProgress: (Int) -> Unit): String =
        withContext(NonCancellable) {
            onProgress(25)
            gate.await()
            onProgress(100)
            "https://example.com/old-upload.mp4"
        }
}

private class NonCooperativeHomeRepository(
    private val result: CompletableDeferred<SingleTaskStatusDto>,
) : HomeRepository {
    private val empty = MutableStateFlow<List<HomeTask>>(emptyList())

    override fun observeActiveTasks(): Flow<List<HomeTask>> = empty

    override fun observeRecentTasks(): Flow<List<HomeTask>> = empty

    override suspend fun generateNote(request: com.bilinote.android.network.dto.GenerateNoteRequest) =
        GenerateNoteResponse(taskId = "task-1", status = "PENDING")

    override suspend fun taskStatus(taskId: String): SingleTaskStatusDto = withContext(NonCancellable) {
        result.await()
    }
}

private class MultiSubmissionRepository(
    private val oldResult: CompletableDeferred<SingleTaskStatusDto>,
) : HomeRepository {
    private val empty = MutableStateFlow<List<HomeTask>>(emptyList())
    private var generation = 0

    override fun observeActiveTasks(): Flow<List<HomeTask>> = empty

    override fun observeRecentTasks(): Flow<List<HomeTask>> = empty

    override suspend fun generateNote(request: com.bilinote.android.network.dto.GenerateNoteRequest): GenerateNoteResponse {
        generation += 1
        return GenerateNoteResponse(taskId = "task-$generation", status = "PENDING")
    }

    override suspend fun taskStatus(taskId: String): SingleTaskStatusDto = if (taskId == "task-1") {
        withContext(NonCancellable) { oldResult.await() }
    } else {
        SingleTaskStatusDto(taskId = taskId, status = "SUCCESS")
    }
}
