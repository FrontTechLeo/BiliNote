package com.bilinote.android.database.entity

import androidx.room.Entity

@Entity(
    tableName = "reading_progress",
    primaryKeys = ["taskId", "serviceBaseUrl"],
)
data class ReadingProgressEntity(
    val taskId: String,
    val serviceBaseUrl: String,
    val scrollOffset: Int,
    val percent: Double,
    val updatedAt: Long,
)
