package com.bilinote.android.library.data

import com.bilinote.android.database.dao.BatchDao
import com.bilinote.android.database.entity.BatchEntity
import com.bilinote.android.network.BiliNoteApi
import com.bilinote.android.network.RepositoryRefreshResult
import com.bilinote.android.network.SyncStatus
import com.bilinote.android.network.dto.BatchTask
import com.bilinote.android.network.dto.BatchTaskPageDto
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

fun interface BatchRemoteLoader {
    suspend fun load(page: Int, pageSize: Int): BatchTaskPageDto
}

class BiliNoteBatchRemoteLoader(private val api: BiliNoteApi) : BatchRemoteLoader {
    override suspend fun load(page: Int, pageSize: Int): BatchTaskPageDto = api.batchTasks(page, pageSize)
}

class LibraryRepository(
    private val batchDao: BatchDao,
    private val serviceBaseUrl: String,
    private val remoteLoader: BatchRemoteLoader,
) {
    constructor(api: BiliNoteApi, batchDao: BatchDao, serviceBaseUrl: String) : this(
        batchDao,
        serviceBaseUrl,
        BiliNoteBatchRemoteLoader(api),
    )

    fun observeAll(): Flow<List<BatchEntity>> = batchDao.observeAll(serviceBaseUrl)

    suspend fun refresh(): RepositoryRefreshResult<List<BatchEntity>> = try {
        val response = remoteLoader.load(page = 1, pageSize = 50)
        batchDao.upsertAll(response.items.orEmpty().mapNotNull { it.toEntity(serviceBaseUrl) })
        RepositoryRefreshResult(batchDao.observeAll(serviceBaseUrl).first(), SyncStatus.Online)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        RepositoryRefreshResult(
            data = batchDao.observeAll(serviceBaseUrl).first(),
            status = SyncStatus.Offline,
            errorMessage = error.message ?: error::class.simpleName ?: "Refresh failed",
        )
    }
}

private fun BatchTask.toEntity(serviceBaseUrl: String): BatchEntity? {
    val batchId = id?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    val createdAtMillis = parseEpochMillis(createdAt)
    val finishedAtMillis = parseEpochMillis(finishedAt)
    return BatchEntity(
        serviceBaseUrl = serviceBaseUrl,
        batchId = batchId,
        title = title.orEmpty(),
        sourceType = sourceType.orEmpty(),
        sourceValue = sourceValue.orEmpty(),
        status = status.orEmpty(),
        totalCount = totalCount ?: 0,
        completedCount = completedCount ?: 0,
        failedCount = failedCount ?: 0,
        runningCount = runningCount ?: 0,
        progress = progress ?: 0.0,
        createdAt = createdAtMillis ?: 0L,
        startedAt = parseEpochMillis(startedAt),
        finishedAt = finishedAtMillis,
        updatedAt = finishedAtMillis ?: createdAtMillis ?: 0L,
    )
}

private fun parseEpochMillis(value: String?): Long? = if (value.isNullOrBlank()) {
    null
} else {
    try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
