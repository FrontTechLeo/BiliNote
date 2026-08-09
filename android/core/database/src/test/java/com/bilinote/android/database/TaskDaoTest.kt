package com.bilinote.android.database

import androidx.test.core.app.ApplicationProvider
import com.bilinote.android.database.dao.BatchDao
import com.bilinote.android.database.dao.TaskDao
import com.bilinote.android.database.entity.BatchEntity
import com.bilinote.android.database.entity.TaskEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
class TaskDaoTest {
    private lateinit var database: BiliNoteDatabase
    private lateinit var taskDao: TaskDao
    private lateinit var batchDao: BatchDao

    @Before
    fun setUp() {
        database = createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        taskDao = database.taskDao()
        batchDao = database.batchDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertUpdatesExistingTaskStatus() = runBlocking {
        taskDao.upsert(task(serviceBaseUrl = "https://a", taskId = "task-1", status = "queued"))
        taskDao.upsert(task(serviceBaseUrl = "https://a", taskId = "task-1", status = "completed"))

        assertEquals("completed", taskDao.observeTask("https://a", "task-1").first()?.status)
    }

    @Test
    fun recentTasksAreDescendingAndCappedAtFifty() = runBlocking {
        (1..51).forEach { index ->
            taskDao.upsert(task(serviceBaseUrl = "https://a", taskId = "task-$index", createdAt = index.toLong()))
        }

        val recent = taskDao.recentTasks("https://a").first()

        assertEquals(50, recent.size)
        assertEquals("task-51", recent.first().taskId)
        assertEquals("task-2", recent.last().taskId)
    }

    @Test
    fun sameTaskIdIsIsolatedBetweenServices() = runBlocking {
        taskDao.upsert(task(serviceBaseUrl = "https://a", taskId = "shared", status = "service-a"))
        taskDao.upsert(task(serviceBaseUrl = "https://b", taskId = "shared", status = "service-b"))

        assertEquals("service-a", taskDao.observeTask("https://a", "shared").first()?.status)
        assertEquals("service-b", taskDao.observeTask("https://b", "shared").first()?.status)
    }

    @Test
    fun activeTasksExcludeTerminalStatusesCaseInsensitively() = runBlocking {
        taskDao.upsert(task(serviceBaseUrl = "https://a", taskId = "completed", status = "completed"))
        taskDao.upsert(task(serviceBaseUrl = "https://a", taskId = "running", status = "RUNNING"))

        assertEquals(listOf("running"), taskDao.activeTasks("https://a").first().map { it.taskId })
    }

    @Test
    fun deletingTasksIsIsolatedByService() = runBlocking {
        taskDao.upsert(task(serviceBaseUrl = "https://a", taskId = "shared"))
        taskDao.upsert(task(serviceBaseUrl = "https://b", taskId = "shared"))

        taskDao.deleteByServiceBaseUrl("https://a")

        assertEquals(null, taskDao.observeTask("https://a", "shared").first())
        assertEquals("shared", taskDao.observeTask("https://b", "shared").first()?.taskId)
    }

    @Test
    fun batchDaoSupportsServiceScopedUpsertAndLookup() = runBlocking {
        batchDao.upsert(batch(serviceBaseUrl = "https://a", batchId = "batch-1"))

        assertEquals("batch-1", batchDao.observe("https://a", "batch-1").first()?.batchId)
        assertEquals(null, batchDao.observe("https://b", "batch-1").first())
    }

    private fun task(
        serviceBaseUrl: String,
        taskId: String,
        status: String = "queued",
        createdAt: Long = 1L,
    ) = TaskEntity(
        serviceBaseUrl = serviceBaseUrl,
        taskId = taskId,
        title = "Task $taskId",
        status = status,
        message = null,
        platform = "bilibili",
        sourceUrl = "https://example.com/$taskId",
        hasResult = false,
        createdAt = createdAt,
        updatedAt = createdAt,
        markdown = null,
    )

    private fun batch(serviceBaseUrl: String, batchId: String) = BatchEntity(
        serviceBaseUrl = serviceBaseUrl,
        batchId = batchId,
        title = "Batch $batchId",
        sourceType = "url",
        sourceValue = "https://example.com/$batchId",
        status = "queued",
        totalCount = 1,
        completedCount = 0,
        failedCount = 0,
        runningCount = 0,
        progress = 0.0,
        createdAt = 1L,
        startedAt = null,
        finishedAt = null,
        updatedAt = 1L,
    )
}
