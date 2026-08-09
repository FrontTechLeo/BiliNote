package com.bilinote.android.home

data class HomeUiState(
    val workflow: HomeWorkflowState = HomeWorkflowState.Idle,
    val activeTasks: List<HomeTask> = emptyList(),
    val recentTasks: List<HomeTask> = emptyList(),
)

sealed interface HomeWorkflowState {
    data object Idle : HomeWorkflowState

    data class Uploading(val percent: Int) : HomeWorkflowState

    data object Submitting : HomeWorkflowState

    data class Polling(val taskId: String, val status: String? = null) : HomeWorkflowState

    data class Success(val taskId: String, val markdown: String? = null) : HomeWorkflowState

    data class Failure(val message: String) : HomeWorkflowState
}

data class HomeTask(
    val taskId: String,
    val title: String,
    val status: String,
    val message: String?,
    val platform: String,
    val sourceUrl: String,
    val hasResult: Boolean,
)

data class ReaderNavigation(val taskId: String)
