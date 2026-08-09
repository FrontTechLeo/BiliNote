package com.bilinote.android.library.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bilinote.android.database.BiliNoteDatabase
import com.bilinote.android.database.entity.BatchEntity
import com.bilinote.android.network.SyncStatus
import com.bilinote.android.network.dto.BatchTask
import com.bilinote.android.network.dto.BatchTaskPageDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryTest {
    private lateinit var database: BiliNoteDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BiliNoteDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun successfulRefreshMapsBatchAndReturnsOnline() = runBlocking {
        val repository = LibraryRepository(
            batchDao = database.batchDao(),
            serviceBaseUrl = "https://service-a",
            remoteLoader = BatchRemoteLoader { page, pageSize ->
                assertEquals(1, page)
                assertEquals(50, pageSize)
                BatchTaskPageDto(items = listOf(batch()))
            },
        )

        val result = repository.refresh()

        val entity = result.data.single()
        assertEquals(SyncStatus.Online, result.status)
        assertNull(result.errorMessage)
        assertEquals("batch-1", entity.batchId)
        assertEquals("https://service-a", entity.serviceBaseUrl)
        assertEquals("Batch title", entity.title)
        assertEquals("url", entity.sourceType)
        assertEquals("https://example.com/list", entity.sourceValue)
        assertEquals("running", entity.status)
        assertEquals(3, entity.totalCount)
        assertEquals(1, entity.completedCount)
        assertEquals(0, entity.failedCount)
        assertEquals(2, entity.runningCount)
        assertEquals(0.33, entity.progress, 0.0)
        assertEquals(1_700_000_000_000L, entity.createdAt)
        assertEquals(1_700_000_060_000L, entity.startedAt)
        assertEquals(1_700_000_120_000L, entity.finishedAt)
        assertEquals(entity.finishedAt, entity.updatedAt)
        assertEquals(result.data, repository.observeAll().first())
    }

    @Test
    fun failedRefreshReturnsAndPreservesExistingBatches() = runBlocking {
        val dao = database.batchDao()
        val cached = batchEntity("batch-cached", "https://service-a")
        dao.upsert(cached)
        val repository = LibraryRepository(
            batchDao = dao,
            serviceBaseUrl = "https://service-a",
            remoteLoader = BatchRemoteLoader { _, _ -> error("batch network unavailable") },
        )

        val result = repository.refresh()

        assertEquals(SyncStatus.Offline, result.status)
        assertEquals("batch network unavailable", result.errorMessage)
        assertEquals(listOf(cached), result.data)
        assertEquals(listOf(cached), repository.observeAll().first())
    }

    @Test
    fun invalidStartedAndFinishedAtRemainNull() = runBlocking {
        val repository = LibraryRepository(
            batchDao = database.batchDao(),
            serviceBaseUrl = "https://service-a",
            remoteLoader = BatchRemoteLoader { _, _ ->
                BatchTaskPageDto(items = listOf(batch().copy(startedAt = "bad", finishedAt = "also-bad")))
            },
        )

        val entity = repository.refresh().data.single()

        assertNull(entity.startedAt)
        assertNull(entity.finishedAt)
        assertEquals(1_700_000_000_000L, entity.createdAt)
        assertEquals(entity.createdAt, entity.updatedAt)
    }

    @Test
    fun batchesAreScopedToServiceBaseUrl() = runBlocking {
        val dao = database.batchDao()
        dao.upsert(batchEntity("shared", "https://service-a"))
        dao.upsert(batchEntity("shared", "https://service-b"))
        val repository = LibraryRepository(
            batchDao = dao,
            serviceBaseUrl = "https://service-a",
            remoteLoader = BatchRemoteLoader { _, _ -> BatchTaskPageDto() },
        )

        assertEquals(listOf("https://service-a"), repository.observeAll().first().map { it.serviceBaseUrl })
    }

    @Test
    fun refreshSkipsBatchWithMissingIdAndKeepsValidBatch() = runBlocking {
        val dao = database.batchDao()
        val cached = batchEntity("batch-1", "https://service-a")
        dao.upsert(cached)
        val repository = LibraryRepository(
            batchDao = dao,
            serviceBaseUrl = "https://service-a",
            remoteLoader = BatchRemoteLoader { _, _ ->
                BatchTaskPageDto(
                    items = listOf(
                        batch().copy(id = null, title = "Invalid null"),
                        batch().copy(id = ""),
                        batch().copy(id = "batch-2", title = "New valid batch"),
                    ),
                )
            },
        )

        val result = repository.refresh()

        assertEquals(listOf("batch-2", "batch-1"), result.data.map { it.batchId })
        assertEquals("batch-1", result.data.last().batchId)
        assertEquals("batch-1", result.data.last().title)
        assertEquals(listOf("batch-2", "batch-1"), repository.observeAll().first().map { it.batchId })
    }

    private fun batch() = BatchTask(
        id = "batch-1",
        title = "Batch title",
        sourceType = "url",
        sourceValue = "https://example.com/list",
        status = "running",
        totalCount = 3,
        completedCount = 1,
        failedCount = 0,
        runningCount = 2,
        progress = 0.33,
        createdAt = "2023-11-14T22:13:20Z",
        startedAt = "2023-11-14T22:14:20Z",
        finishedAt = "2023-11-14T22:15:20Z",
    )

    private fun batchEntity(batchId: String, serviceBaseUrl: String) = BatchEntity(
        serviceBaseUrl = serviceBaseUrl,
        batchId = batchId,
        title = batchId,
        sourceType = "url",
        sourceValue = "",
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
