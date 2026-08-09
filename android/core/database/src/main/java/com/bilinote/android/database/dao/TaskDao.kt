package com.bilinote.android.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.bilinote.android.database.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE serviceBaseUrl = :serviceBaseUrl ORDER BY createdAt DESC, taskId DESC LIMIT 50")
    fun recentTasks(serviceBaseUrl: String): Flow<List<TaskEntity>>

    @Query(
        "SELECT * FROM tasks " +
            "WHERE serviceBaseUrl = :serviceBaseUrl " +
            "AND UPPER(status) NOT IN ('SUCCESS', 'COMPLETED', 'DONE', 'FAILED', 'FAILURE', 'ERROR', 'CANCELLED') " +
            "ORDER BY createdAt DESC, taskId DESC",
    )
    fun activeTasks(serviceBaseUrl: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE serviceBaseUrl = :serviceBaseUrl AND taskId = :taskId LIMIT 1")
    fun observeTask(serviceBaseUrl: String, taskId: String): Flow<TaskEntity?>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Upsert
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE serviceBaseUrl = :serviceBaseUrl")
    suspend fun deleteByServiceBaseUrl(serviceBaseUrl: String)

    @Query("DELETE FROM tasks WHERE serviceBaseUrl = :serviceBaseUrl AND taskId = :taskId")
    suspend fun delete(serviceBaseUrl: String, taskId: String)
}
