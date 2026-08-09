package com.bilinote.android.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.bilinote.android.database.entity.BatchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchDao {
    @Query("SELECT * FROM batches WHERE serviceBaseUrl = :serviceBaseUrl ORDER BY createdAt DESC, batchId DESC LIMIT 50")
    fun observeAll(serviceBaseUrl: String): Flow<List<BatchEntity>>

    @Query("SELECT * FROM batches WHERE serviceBaseUrl = :serviceBaseUrl AND batchId = :batchId LIMIT 1")
    fun observe(serviceBaseUrl: String, batchId: String): Flow<BatchEntity?>

    @Upsert
    suspend fun upsert(batch: BatchEntity)

    @Upsert
    suspend fun upsertAll(batches: List<BatchEntity>)

    @Query("DELETE FROM batches WHERE serviceBaseUrl = :serviceBaseUrl")
    suspend fun deleteByServiceBaseUrl(serviceBaseUrl: String)

    @Transaction
    suspend fun replaceAll(serviceBaseUrl: String, batches: List<BatchEntity>) {
        deleteByServiceBaseUrl(serviceBaseUrl)
        upsertAll(batches)
    }

    @Query("DELETE FROM batches WHERE serviceBaseUrl = :serviceBaseUrl AND batchId = :batchId")
    suspend fun delete(serviceBaseUrl: String, batchId: String)
}
