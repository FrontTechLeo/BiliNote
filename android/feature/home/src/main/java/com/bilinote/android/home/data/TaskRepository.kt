package com.bilinote.android.home.data

import com.bilinote.android.database.dao.TaskDao
import com.bilinote.android.database.entity.TaskEntity
import com.bilinote.android.network.BiliNoteApi
import com.bilinote.android.network.ApiHttpException
import com.bilinote.android.network.RepositoryRefreshResult
import com.bilinote.android.network.SyncStatus
import com.bilinote.android.network.dto.GenerateNoteRequest
import com.bilinote.android.network.dto.GenerateNoteResponse
import com.bilinote.android.network.dto.SingleTaskHistoryDto
import com.bilinote.android.network.dto.SingleTaskPageDto
import com.bilinote.android.network.dto.SingleTaskStatusDto
import java.time.Instant
import java.time.format.DateTimeParseException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

fun interface TaskRemoteLoader {
    suspend fun load(page: Int, pageSize: Int): SingleTaskPageDto
}

class BiliNoteTaskRemoteLoader(private val api: BiliNoteApi) : TaskRemoteLoader {
    override suspend fun load(page: Int, pageSize: Int): SingleTaskPageDto = api.singleTasks(page, pageSize)
}

class TaskRepository(
    private val taskDao: TaskDao,
    private val serviceBaseUrl: String,
    private val remoteLoader: TaskRemoteLoader,
) {
    constructor(api: BiliNoteApi, taskDao: TaskDao, serviceBaseUrl: String) : this(
        taskDao,
        serviceBaseUrl,
        BiliNoteTaskRemoteLoader(api),
    )

    fun recent(): Flow<List<TaskEntity>> = taskDao.recentTasks(serviceBaseUrl)

    fun active(): Flow<List<TaskEntity>> = taskDao.activeTasks(serviceBaseUrl)

    suspend fun upsertGeneratedTask(
        taskId: String,
        request: GenerateNoteRequest,
        response: GenerateNoteResponse,
    ) {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) return
        val existing = taskDao.observeTask(serviceBaseUrl, normalizedTaskId).first()
        val now = System.currentTimeMillis()
        val requestSourceUrl = request.videoUrl.trim()
        val responseStatus = response.status?.trim().orEmpty()
        val markdown = response.result?.markdown ?: existing?.markdown
        taskDao.upsert(
            TaskEntity(
                serviceBaseUrl = serviceBaseUrl,
                taskId = normalizedTaskId,
                title = existing?.title?.ifBlank { requestSourceUrl }
                    ?: requestSourceUrl.ifBlank { normalizedTaskId },
                status = responseStatus.ifBlank { existing?.status ?: "PENDING" },
                message = response.message ?: existing?.message,
                platform = request.platform.trim().ifBlank { existing?.platform.orEmpty() },
                sourceUrl = requestSourceUrl.ifBlank { existing?.sourceUrl.orEmpty() },
                hasResult = response.result?.markdown != null ||
                    existing?.hasResult == true ||
                    responseStatus.uppercase() in SUCCESS_STATUSES,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                markdown = markdown,
            ),
        )
    }

    suspend fun updateTaskStatus(status: SingleTaskStatusDto) {
        val normalizedTaskId = status.taskId?.trim().takeUnless { it.isNullOrEmpty() } ?: return
        val existing = taskDao.observeTask(serviceBaseUrl, normalizedTaskId).first()
        val now = System.currentTimeMillis()
        val remoteStatus = status.status?.trim().orEmpty()
        val remotePlatform = status.platform?.trim().orEmpty()
        val remoteSourceUrl = status.sourceUrl?.trim().orEmpty()
        val markdown = status.result?.markdown ?: existing?.markdown
        taskDao.upsert(
            TaskEntity(
                serviceBaseUrl = serviceBaseUrl,
                taskId = normalizedTaskId,
                title = existing?.title ?: remoteSourceUrl.ifBlank { normalizedTaskId },
                status = remoteStatus.ifBlank { existing?.status ?: "PENDING" },
                message = status.message ?: existing?.message,
                platform = remotePlatform.ifBlank { existing?.platform.orEmpty() },
                sourceUrl = remoteSourceUrl.ifBlank { existing?.sourceUrl.orEmpty() },
                hasResult = status.result?.markdown != null ||
                    existing?.hasResult == true ||
                    remoteStatus.uppercase() in SUCCESS_STATUSES,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                markdown = markdown,
            ),
        )
    }

    suspend fun refresh(): RepositoryRefreshResult<List<TaskEntity>> = try {
        val response = remoteLoader.load(page = 1, pageSize = 50)
        val refreshedTasks = response.items.orEmpty()
            .mapNotNull { it.toEntity(serviceBaseUrl) }
            .map { task ->
                val cachedMarkdown = taskDao
                    .observeTask(serviceBaseUrl, task.taskId)
                    .first()
                    ?.markdown
                task.copy(markdown = task.markdown ?: cachedMarkdown)
            }
        taskDao.upsertAll(refreshedTasks)
        RepositoryRefreshResult(taskDao.recentTasks(serviceBaseUrl).first(), SyncStatus.Online)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        RepositoryRefreshResult(
            data = taskDao.recentTasks(serviceBaseUrl).first(),
            status = SyncStatus.Offline,
            errorMessage = error.message ?: error::class.simpleName ?: "Refresh failed",
            retryable = when (error) {
                is ApiHttpException -> error.statusCode >= 500
                is IOException -> true
                else -> false
            },
        )
    }
}

private fun SingleTaskHistoryDto.toEntity(serviceBaseUrl: String): TaskEntity? {
    val id = taskId?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    val createdAtMillis = parseEpochMillis(createdAt)
    return TaskEntity(
        serviceBaseUrl = serviceBaseUrl,
        taskId = id,
        title = title.orEmpty(),
        status = status.orEmpty(),
        message = message,
        platform = platform.orEmpty(),
        sourceUrl = sourceUrl.orEmpty(),
        hasResult = hasResult == true,
        createdAt = createdAtMillis,
        updatedAt = createdAtMillis,
        markdown = null,
    )
}

private fun parseEpochMillis(value: String?): Long = if (value.isNullOrBlank()) {
    0L
} else {
    try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        0L
    }
}

private val SUCCESS_STATUSES = setOf("SUCCESS", "COMPLETED", "DONE")
