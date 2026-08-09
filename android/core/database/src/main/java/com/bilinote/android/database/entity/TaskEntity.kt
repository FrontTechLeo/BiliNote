package com.bilinote.android.database.entity

import androidx.room.Entity

@Entity(
    tableName = "tasks",
    primaryKeys = ["serviceBaseUrl", "taskId"],
)
data class TaskEntity(
    val serviceBaseUrl: String,
    val taskId: String,
    val title: String,
    val status: String,
    val message: String?,
    val platform: String,
    val sourceUrl: String,
    val hasResult: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val markdown: String?,
)
