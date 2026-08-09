package com.bilinote.android.database

import android.content.Context
import androidx.room.Room

internal fun createInMemoryDatabase(context: Context): BiliNoteDatabase =
    Room.inMemoryDatabaseBuilder(context, BiliNoteDatabase::class.java)
        .allowMainThreadQueries()
        .build()
