package com.bilinote.android.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.bilinote.android.database.dao.BatchDao
import com.bilinote.android.database.dao.ReadingProgressDao
import com.bilinote.android.database.dao.TaskDao
import com.bilinote.android.database.entity.BatchEntity
import com.bilinote.android.database.entity.ReadingProgressEntity
import com.bilinote.android.database.entity.TaskEntity

@Database(
    entities = [TaskEntity::class, BatchEntity::class, ReadingProgressEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class BiliNoteDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    abstract fun batchDao(): BatchDao

    abstract fun readingProgressDao(): ReadingProgressDao
}
