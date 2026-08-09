package com.bilinote.android.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.bilinote.android.database.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {
    @Query(
        "SELECT * FROM reading_progress " +
            "WHERE taskId = :taskId AND serviceBaseUrl = :serviceBaseUrl LIMIT 1",
    )
    fun observe(taskId: String, serviceBaseUrl: String): Flow<ReadingProgressEntity?>

    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query(
        "DELETE FROM reading_progress " +
            "WHERE taskId = :taskId AND serviceBaseUrl = :serviceBaseUrl",
    )
    suspend fun delete(taskId: String, serviceBaseUrl: String)

    @Query("DELETE FROM reading_progress WHERE serviceBaseUrl = :serviceBaseUrl")
    suspend fun deleteByServiceBaseUrl(serviceBaseUrl: String)
}
