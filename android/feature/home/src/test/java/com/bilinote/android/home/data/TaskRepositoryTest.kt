package com.bilinote.android.home.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bilinote.android.database.BiliNoteDatabase
import com.bilinote.android.database.entity.TaskEntity
import com.bilinote.android.network.ApiHttpException
import com.bilinote.android.network.SyncStatus
import com.bilinote.android.network.dto.GenerateNoteRequest
import com.bilinote.android.network.dto.GenerateNoteResponse
import com.bilinote.android.network.dto.SingleTaskHistoryDto
import com.bilinote.android.network.dto.SingleTaskPageDto
import com.bilinote.android.network.dto.SingleTaskStatusDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskRepositoryTest {
    private lateinit var database: BiliNoteDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BiliNoteDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun successfulRefreshWritesMappedTasksAndReturnsOnline() = runBlocking {
        val repository = TaskRepository(
            taskDao = database.taskDao(),
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { page, pageSize ->
                assertEquals(1, page)
                assertEquals(50, pageSize)
                SingleTaskPageDto(items = listOf(remoteTask()))
            },
        )

        val result = repository.refresh()
        val task = result.data.single()

        assertEquals(SyncStatus.Online, result.status)
        assertNull(result.errorMessage)
        assertEquals("task-1", task.taskId)
        assertEquals("https://service-a", task.serviceBaseUrl)
        assertEquals("Task title", task.title)
        assertEquals("queued", task.status)
        assertEquals("waiting", task.message)
        assertEquals("bilibili", task.platform)
        assertEquals("https://example.com/video", task.sourceUrl)
        assertEquals(true, task.hasResult)
        assertEquals(1_700_000_000_000L, task.createdAt)
        assertEquals(task.createdAt, task.updatedAt)
        assertNull(task.markdown)
        assertEquals(result.data, repository.recent().first())
    }

    @Test
    fun invalidCreatedAtFallsBackToZero() = runBlocking {
        val repository = TaskRepository(
            taskDao = database.taskDao(),
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { _, _ ->
                SingleTaskPageDto(items = listOf(remoteTask().copy(createdAt = "not-a-time")))
            },
        )

        val task = repository.refresh().data.single()

        assertEquals(0L, task.createdAt)
        assertEquals(0L, task.updatedAt)
    }

    @Test
    fun failedRefreshReturnsExistingCacheAsOfflineWithoutClearingIt() = runBlocking {
        val dao = database.taskDao()
        val cached = cachedTask("cached", "https://service-a")
        dao.upsert(cached)
        val repository = TaskRepository(
            taskDao = dao,
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { _, _ -> error("network unavailable") },
        )

        val result = repository.refresh()

        assertEquals(SyncStatus.Offline, result.status)
        assertEquals("network unavailable", result.errorMessage)
        assertEquals(listOf(cached), result.data)
        assertEquals(listOf(cached), repository.recent().first())
    }

    @Test
    fun unauthorizedRefreshIsOfflineAndNotRetryable() = runBlocking {
        val repository = TaskRepository(
            taskDao = database.taskDao(),
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { _, _ -> throw ApiHttpException(401, "unauthorized") },
        )

        val result = repository.refresh()

        assertEquals(SyncStatus.Offline, result.status)
        assertEquals(false, result.retryable)
        assertEquals("unauthorized", result.errorMessage)
    }

    @Test
    fun serverRefreshFailureIsRetryable() = runBlocking {
        val repository = TaskRepository(
            taskDao = database.taskDao(),
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { _, _ -> throw ApiHttpException(503, "unavailable") },
        )

        assertEquals(true, repository.refresh().retryable)
    }

    @Test
    fun connectionRefreshFailureIsRetryable() = runBlocking {
        val repository = TaskRepository(
            taskDao = database.taskDao(),
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { _, _ -> throw IOException("offline") },
        )

        assertEquals(true, repository.refresh().retryable)
    }

    @Test
    fun refreshPreservesCachedMarkdownForExistingTask() = runBlocking {
        val dao = database.taskDao()
        val cached = cachedTask("task-1", "https://service-a").copy(markdown = "# cached note")
        dao.upsert(cached)
        val repository = TaskRepository(
            taskDao = dao,
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { _, _ ->
                SingleTaskPageDto(items = listOf(remoteTask()))
            },
        )

        val result = repository.refresh()

        assertEquals("# cached note", result.data.single().markdown)
        assertEquals("# cached note", dao.observeTask("https://service-a", "task-1").first()?.markdown)
    }

    @Test
    fun recentFlowIsScopedToServiceBaseUrl() = runBlocking {
        val dao = database.taskDao()
        dao.upsert(cachedTask("shared", "https://service-a"))
        dao.upsert(cachedTask("shared", "https://service-b"))
        val repository = TaskRepository(
            taskDao = dao,
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { _, _ -> SingleTaskPageDto() },
        )

        assertEquals(listOf("https://service-a"), repository.recent().first().map { it.serviceBaseUrl })
    }

    @Test
    fun refreshSkipsTaskWithMissingIdAndKeepsValidTask() = runBlocking {
        val dao = database.taskDao()
        val cached = cachedTask("task-1", "https://service-a")
        dao.upsert(cached)
        val repository = TaskRepository(
            taskDao = dao,
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { _, _ ->
                SingleTaskPageDto(
                    items = listOf(
                        remoteTask().copy(taskId = null, title = "Invalid null"),
                        remoteTask().copy(taskId = " "),
                        remoteTask().copy(taskId = "task-2", title = "New valid task"),
                    ),
                )
            },
        )

        val result = repository.refresh()

        assertEquals(listOf("task-2", "task-1"), result.data.map { it.taskId })
        assertEquals("task-1", result.data.last().taskId)
        assertEquals("task-1", result.data.last().title)
        assertEquals(listOf("task-2", "task-1"), repository.recent().first().map { it.taskId })
    }

    @Test
    fun generatedTaskIsPersistedWithRequestAndResponseFields() = runBlocking {
        val repository = TaskRepository(
            taskDao = database.taskDao(),
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { _, _ -> SingleTaskPageDto() },
        )

        repository.upsertGeneratedTask(
            taskId = "task-created",
            request = GenerateNoteRequest(
                videoUrl = "https://example.com/video",
                platform = "bilibili",
                quality = "medium",
                modelName = "model-1",
                providerId = "provider-1",
            ),
            response = GenerateNoteResponse(status = "PENDING", message = "queued"),
        )

        val task = database.taskDao().observeTask("https://service-a", "task-created").first()
        requireNotNull(task)
        assertEquals("PENDING", task.status)
        assertEquals("queued", task.message)
        assertEquals("bilibili", task.platform)
        assertEquals("https://example.com/video", task.sourceUrl)
        assertEquals(false, task.hasResult)
    }

    @Test
    fun successfulStatusUpdatesTaskAndKeepsExistingContext() = runBlocking {
        val dao = database.taskDao()
        dao.upsert(cachedTask("task-1", "https://service-a").copy(
            title = "Existing title",
            message = "queued",
            sourceUrl = "https://example.com/video",
            markdown = null,
        ))
        val repository = TaskRepository(
            taskDao = dao,
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { _, _ -> SingleTaskPageDto() },
        )

        repository.updateTaskStatus(
            SingleTaskStatusDto(
                taskId = "task-1",
                status = "SUCCESS",
                result = com.bilinote.android.network.dto.SingleTaskResultDto(markdown = "# note"),
            ),
        )

        val task = dao.observeTask("https://service-a", "task-1").first()
        requireNotNull(task)
        assertEquals("SUCCESS", task.status)
        assertEquals("Existing title", task.title)
        assertEquals("https://example.com/video", task.sourceUrl)
        assertEquals("# note", task.markdown)
        assertEquals(true, task.hasResult)
    }

    @Test
    fun failedStatusUpdatesMessageWithoutDiscardingCachedMarkdown() = runBlocking {
        val dao = database.taskDao()
        dao.upsert(cachedTask("task-1", "https://service-a").copy(
            message = "queued",
            sourceUrl = "https://example.com/video",
            markdown = "# cached note",
            hasResult = true,
        ))
        val repository = TaskRepository(
            taskDao = dao,
            serviceBaseUrl = "https://service-a",
            remoteLoader = TaskRemoteLoader { _, _ -> SingleTaskPageDto() },
        )

        repository.updateTaskStatus(
            SingleTaskStatusDto(
                taskId = "task-1",
                status = "FAILED",
                message = "generation failed",
            ),
        )

        val task = dao.observeTask("https://service-a", "task-1").first()
        requireNotNull(task)
        assertEquals("FAILED", task.status)
        assertEquals("generation failed", task.message)
        assertEquals("https://example.com/video", task.sourceUrl)
        assertEquals("# cached note", task.markdown)
        assertEquals(true, task.hasResult)
    }

    private fun remoteTask() = SingleTaskHistoryDto(
        taskId = "task-1",
        title = "Task title",
        platform = "bilibili",
        sourceUrl = "https://example.com/video",
        status = "queued",
        message = "waiting",
        createdAt = "2023-11-14T22:13:20Z",
        hasResult = true,
    )

    private fun cachedTask(taskId: String, serviceBaseUrl: String) = TaskEntity(
        serviceBaseUrl = serviceBaseUrl,
        taskId = taskId,
        title = taskId,
        status = "running",
        message = null,
        platform = "bilibili",
        sourceUrl = "",
        hasResult = false,
        createdAt = 1L,
        updatedAt = 1L,
        markdown = null,
    )
}
