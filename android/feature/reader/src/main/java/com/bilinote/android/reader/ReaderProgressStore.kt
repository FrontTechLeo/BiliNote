package com.bilinote.android.reader

import com.bilinote.android.database.dao.ReadingProgressDao
import com.bilinote.android.database.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ProgressStore {
    fun observe(taskId: String, serviceBaseUrl: String): Flow<ReadingProgressEntity?>

    suspend fun saveThrottled(progress: ReadingProgressEntity)

    suspend fun saveNow(progress: ReadingProgressEntity)
}

class ReaderProgressStore(
    private val dao: ReadingProgressDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ProgressStore {
    private data class Key(val taskId: String, val serviceBaseUrl: String)

    private val mutex = Mutex()
    private val lastSavedAt = mutableMapOf<Key, Long>()
    private val latest = mutableMapOf<Key, ReadingProgressEntity>()

    override fun observe(taskId: String, serviceBaseUrl: String): Flow<ReadingProgressEntity?> =
        dao.observe(taskId, serviceBaseUrl)

    override suspend fun saveThrottled(progress: ReadingProgressEntity) {
        val key = Key(progress.taskId, progress.serviceBaseUrl)
        val now = clock()
        val shouldSave = mutex.withLock {
            latest[key] = progress
            val previous = lastSavedAt[key]
            if (previous == null || now - previous >= SAVE_INTERVAL_MILLIS) {
                lastSavedAt[key] = now
                true
            } else {
                false
            }
        }
        if (shouldSave) dao.upsert(progress)
    }

    override suspend fun saveNow(progress: ReadingProgressEntity) {
        val key = Key(progress.taskId, progress.serviceBaseUrl)
        mutex.withLock {
            latest[key] = progress
            lastSavedAt[key] = clock()
        }
        dao.upsert(progress)
    }

    suspend fun flush(taskId: String, serviceBaseUrl: String) {
        val key = Key(taskId, serviceBaseUrl)
        val pending = mutex.withLock { latest[key] }
        if (pending != null) saveNow(pending)
    }

    private companion object {
        const val SAVE_INTERVAL_MILLIS = 180L
    }
}
