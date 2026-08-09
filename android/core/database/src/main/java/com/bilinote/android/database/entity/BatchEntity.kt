package com.bilinote.android.database.entity

import androidx.room.Entity

@Entity(
    tableName = "batches",
    primaryKeys = ["serviceBaseUrl", "batchId"],
)
data class BatchEntity(
    val serviceBaseUrl: String,
    val batchId: String,
    val title: String,
    val sourceType: String,
    val sourceValue: String,
    val status: String,
    val totalCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val runningCount: Int,
    val progress: Double,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val updatedAt: Long,
)
