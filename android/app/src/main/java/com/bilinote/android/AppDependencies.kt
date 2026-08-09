package com.bilinote.android

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.bilinote.android.database.BiliNoteDatabase
import com.bilinote.android.database.dao.BatchDao
import com.bilinote.android.database.entity.BatchEntity
import com.bilinote.android.feature.batch.BatchCache
import com.bilinote.android.feature.batch.BatchTask
import com.bilinote.android.feature.batch.BatchViewModel
import com.bilinote.android.feature.batch.RetrofitBatchRepository
import com.bilinote.android.feature.batch.defaultBatchConfig
import com.bilinote.android.database.entity.TaskEntity
import com.bilinote.android.home.BiliNoteHomeRepository
import com.bilinote.android.home.BiliNoteLocalVideoUploader
import com.bilinote.android.home.HomeViewModel
import com.bilinote.android.home.data.TaskRepository
import com.bilinote.android.library.LibraryViewModel
import com.bilinote.android.library.TaskRepositoryLibraryTaskDataSource
import com.bilinote.android.reader.BiliNoteReaderDataSource
import com.bilinote.android.reader.ReaderProgressStore
import com.bilinote.android.reader.ReaderViewModel
import com.bilinote.android.feature.settings.SavedSettings
import com.bilinote.android.feature.settings.SettingsRepository
import com.bilinote.android.feature.settings.SettingsViewModel
import com.bilinote.android.network.BiliNoteApi
import com.bilinote.android.network.BackendUrl
import com.bilinote.android.network.NetworkModule
import com.bilinote.android.network.SyncStatus
import com.bilinote.android.network.RetrofitBiliNoteApi
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class AppDependencies(context: Context) {
    val settingsRepository = SettingsRepository(context.applicationContext)
    val database: BiliNoteDatabase = Room.databaseBuilder(
        context.applicationContext,
        BiliNoteDatabase::class.java,
        "bilinote.db",
    ).build()

    fun configuredBackend(settings: SavedSettings): BackendConfiguration {
        if (settings.backendUrl.isBlank()) {
            return BackendConfiguration(errorMessage = "请先配置服务地址")
        }
        val serviceBaseUrl = try {
            BackendUrl.normalize(settings.backendUrl)
        } catch (exception: IllegalArgumentException) {
            return BackendConfiguration(errorMessage = exception.message ?: "服务地址无效")
        }

        return BackendConfiguration(
            backend = ConfiguredBackend(
                database = database,
                serviceBaseUrl = serviceBaseUrl,
                accessToken = settings.accessToken,
                identity = "$serviceBaseUrl:${digestToken(settings.accessToken)}",
                api = BiliNoteApi(
                    NetworkModule.createRetrofit(serviceBaseUrl, settings.accessToken)
                        .create(RetrofitBiliNoteApi::class.java),
                ),
            ),
        )
    }

    fun settingsViewModelFactory(): ViewModelProvider.Factory = factory {
        SettingsViewModel(settingsRepository)
    }

    fun close() {
        database.close()
    }
}

data class BackendConfiguration(
    val backend: ConfiguredBackend? = null,
    val errorMessage: String? = null,
)

class ConfiguredBackend internal constructor(
    private val database: BiliNoteDatabase,
    val serviceBaseUrl: String,
    val accessToken: String?,
    val identity: String,
    val api: BiliNoteApi,
) {
    private val taskRepository = TaskRepository(api, database.taskDao(), serviceBaseUrl)
    private val batchRepository = RetrofitBatchRepository(api, serviceBaseUrl)
    private val batchCache: BatchCache = RoomBatchCache(database.batchDao())
    private val readerDataSource = BiliNoteReaderDataSource(api, database.taskDao())
    private val progressStore = ReaderProgressStore(database.readingProgressDao())

    fun homeViewModelFactory(): ViewModelProvider.Factory = factory {
        HomeViewModel(
            repository = BiliNoteHomeRepository(api, taskRepository),
            uploader = BiliNoteLocalVideoUploader(api),
        )
    }

    fun libraryViewModelFactory(): ViewModelProvider.Factory = factory {
        LibraryViewModel(TaskRepositoryLibraryTaskDataSource(taskRepository))
    }

    fun readerViewModelFactory(taskId: String): ViewModelProvider.Factory = factory {
        ReaderViewModel(
            taskId = taskId,
            serviceBaseUrl = serviceBaseUrl,
            dataSource = readerDataSource,
            progressStore = progressStore,
        )
    }

    fun batchViewModelFactory(): ViewModelProvider.Factory = factory {
        BatchViewModel(repository = batchRepository, cache = batchCache)
    }

    suspend fun syncActiveSnapshots() {
        val taskRefresh = taskRepository.refresh()
        if (taskRefresh.status != SyncStatus.Online) {
            val message = taskRefresh.errorMessage ?: "任务同步失败"
            if (taskRefresh.retryable) {
                throw java.io.IOException(message)
            }
            throw IllegalStateException(message)
        }
        val batchPage = batchRepository.batchTasks(page = 1, pageSize = 50)
        batchCache.saveTasks(serviceBaseUrl, batchPage.items)
    }

    @Deprecated("Use syncActiveSnapshots for background snapshot refreshes")
    suspend fun syncSnapshots() = syncActiveSnapshots()

    fun observeRecentTasks(): Flow<List<TaskEntity>> = taskRepository.recent()
}

private class RoomBatchCache(
    private val batchDao: BatchDao,
) : BatchCache {
    override suspend fun loadTasks(serviceBaseUrl: String): List<BatchTask> =
        batchDao.observeAll(serviceBaseUrl).first().map { it.toBatchTask() }

    override suspend fun saveTasks(serviceBaseUrl: String, tasks: List<BatchTask>) {
        batchDao.replaceAll(serviceBaseUrl, tasks.mapNotNull { it.toBatchEntity(serviceBaseUrl) })
    }
}

private fun BatchTask.toBatchEntity(serviceBaseUrl: String): BatchEntity? {
    val batchId = id?.trim()?.takeUnless(String::isEmpty) ?: return null
    val createdAt = parseEpochMillis(createdAt) ?: 0L
    return BatchEntity(
        serviceBaseUrl = serviceBaseUrl,
        batchId = batchId,
        title = title.orEmpty(),
        sourceType = sourceType.orEmpty(),
        sourceValue = sourceValue.orEmpty(),
        status = status.orEmpty(),
        totalCount = totalCount,
        completedCount = completedCount,
        failedCount = failedCount,
        runningCount = runningCount,
        progress = progress,
        createdAt = createdAt,
        startedAt = parseEpochMillis(startedAt),
        finishedAt = parseEpochMillis(finishedAt),
        updatedAt = parseEpochMillis(finishedAt) ?: parseEpochMillis(startedAt) ?: createdAt,
    )
}

private fun BatchEntity.toBatchTask(): BatchTask = BatchTask(
    id = batchId,
    title = title,
    sourceType = sourceType,
    sourceValue = sourceValue,
    status = status,
    totalCount = totalCount,
    completedCount = completedCount,
    failedCount = failedCount,
    runningCount = runningCount,
    progress = progress,
    createdAt = createdAt.toString(),
    startedAt = startedAt?.toString(),
    finishedAt = finishedAt?.toString(),
    config = defaultBatchConfig,
)

private fun parseEpochMillis(value: String?): Long? {
    val normalized = value?.trim().orEmpty()
    if (normalized.isEmpty()) return null
    normalized.toLongOrNull()?.let { return it }
    return runCatching { Instant.parse(normalized).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

private fun <T : ViewModel> factory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
            @Suppress("UNCHECKED_CAST")
            return create() as VM
        }
    }

private fun digestToken(token: String?): String {
    val value = token?.trim().orEmpty()
    if (value.isEmpty()) return "none"
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
}
