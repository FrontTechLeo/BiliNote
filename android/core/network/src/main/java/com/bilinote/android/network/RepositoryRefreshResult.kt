package com.bilinote.android.network

enum class SyncStatus { Online, Offline }

data class RepositoryRefreshResult<T>(
    val data: T,
    val status: SyncStatus,
    val errorMessage: String? = null,
    val retryable: Boolean = false,
)
