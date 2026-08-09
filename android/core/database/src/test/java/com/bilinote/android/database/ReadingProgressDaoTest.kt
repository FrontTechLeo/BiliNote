package com.bilinote.android.database

import androidx.test.core.app.ApplicationProvider
import com.bilinote.android.database.dao.ReadingProgressDao
import com.bilinote.android.database.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingProgressDaoTest {
    private lateinit var database: BiliNoteDatabase
    private lateinit var progressDao: ReadingProgressDao

    @Before
    fun setUp() {
        database = createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        progressDao = database.readingProgressDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun progressIsScopedByServiceAndTaskCompositeKey() = runBlocking {
        progressDao.upsert(
            ReadingProgressEntity(
                taskId = "task-1",
                serviceBaseUrl = "https://a",
                scrollOffset = 120,
                percent = 0.25,
                updatedAt = 1L,
            ),
        )

        assertEquals(
            0.25,
            requireNotNull(progressDao.observe("task-1", "https://a").first()).percent,
            0.0,
        )
        assertEquals(null, progressDao.observe("task-1", "https://b").first())
    }

    @Test
    fun upsertUpdatesProgressForSameCompositeKey() = runBlocking {
        progressDao.upsert(progress("https://a", 0.25))
        progressDao.upsert(progress("https://a", 0.75))

        assertEquals(
            0.75,
            requireNotNull(progressDao.observe("task-1", "https://a").first()).percent,
            0.0,
        )
    }

    @Test
    fun deletingProgressIsIsolatedByService() = runBlocking {
        progressDao.upsert(progress("https://a", 0.25))
        progressDao.upsert(progress("https://b", 0.75))

        progressDao.deleteByServiceBaseUrl("https://a")

        assertEquals(null, progressDao.observe("task-1", "https://a").first())
        assertEquals(
            0.75,
            requireNotNull(progressDao.observe("task-1", "https://b").first()).percent,
            0.0,
        )
    }

    private fun progress(serviceBaseUrl: String, percent: Double) = ReadingProgressEntity(
        taskId = "task-1",
        serviceBaseUrl = serviceBaseUrl,
        scrollOffset = 120,
        percent = percent,
        updatedAt = 1L,
    )
}
