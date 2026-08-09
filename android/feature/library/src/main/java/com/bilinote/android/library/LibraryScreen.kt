package com.bilinote.android.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilinote.android.core.designsystem.AdaptiveListDetailPane
import com.bilinote.android.core.designsystem.BiliNoteTheme

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    windowWidthSizeClass: WindowWidthSizeClass,
    onOpenReader: (taskId: String) -> Unit,
    onExport: (taskId: String) -> Unit,
    onRegenerate: (taskId: String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BiliNoteTheme {
        LibraryContent(
            uiState = uiState,
            windowWidthSizeClass = windowWidthSizeClass,
            onRefresh = viewModel::refresh,
            onSelectTask = viewModel::selectTask,
            onOpenReader = onOpenReader,
            onExport = onExport,
            onRegenerate = onRegenerate,
        )
    }
}

@Composable
private fun LibraryContent(
    uiState: LibraryUiState,
    windowWidthSizeClass: WindowWidthSizeClass,
    onRefresh: () -> Unit,
    onSelectTask: (String) -> Unit,
    onOpenReader: (taskId: String) -> Unit,
    onExport: (taskId: String) -> Unit,
    onRegenerate: (taskId: String) -> Unit,
) {
    val selectedTask = uiState.tasks.firstOrNull { it.taskId == uiState.selectedTaskId }
    AdaptiveListDetailPane(
        windowWidthSizeClass = windowWidthSizeClass,
        modifier = Modifier
            .fillMaxSize()
            .testTag("library-screen")
            .semantics { contentDescription = "Library screen" },
        content = {
            LibraryListPane(
                uiState = uiState,
                onRefresh = onRefresh,
                onSelectTask = onSelectTask,
            )
        },
        listContent = {
            LibraryListPane(
                uiState = uiState,
                onRefresh = onRefresh,
                onSelectTask = onSelectTask,
            )
        },
        detailContent = {
            LibraryDetailPane(
                task = selectedTask,
                onOpenReader = onOpenReader,
                onExport = onExport,
                onRegenerate = onRegenerate,
            )
        },
    )
}

@Composable
private fun LibraryListPane(
    uiState: LibraryUiState,
    onRefresh: () -> Unit,
    onSelectTask: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("library-list")
            .semantics { contentDescription = "Library task list" }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library", style = MaterialTheme.typography.headlineMedium)
            Button(
                onClick = onRefresh,
                enabled = !uiState.isRefreshing,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("library-refresh")
                    .semantics { contentDescription = "Refresh library" },
            ) {
                Text(if (uiState.isRefreshing) "Refreshing" else "Refresh")
            }
        }

        if (uiState.syncStatus == com.bilinote.android.network.SyncStatus.Offline) {
            Text(
                text = "Offline${uiState.errorMessage?.let { ": $it" }.orEmpty()}",
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("library-offline")
                    .semantics { contentDescription = "Offline library cache" },
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (uiState.tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("library-empty")
                    .semantics { contentDescription = "Library empty state" },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (uiState.isRefreshing) "Loading tasks" else "No tasks")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.tasks, key = { it.taskId }) { task ->
                    LibraryTaskRow(
                        task = task,
                        selected = task.taskId == uiState.selectedTaskId,
                        onClick = { onSelectTask(task.taskId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTaskRow(
    task: LibraryTask,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .clickable(onClick = onClick)
            .testTag("library-task-${task.taskId}")
            .semantics { contentDescription = "Task ${task.title}, ${task.status}" }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = task.title.ifBlank { task.taskId },
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text("Status: ${task.status.ifBlank { "UNKNOWN" }}")
        task.message?.takeIf { it.isNotBlank() }?.let { Text(it) }
        HorizontalDivider()
    }
}

@Composable
private fun LibraryDetailPane(
    task: LibraryTask?,
    onOpenReader: (taskId: String) -> Unit,
    onExport: (taskId: String) -> Unit,
    onRegenerate: (taskId: String) -> Unit,
) {
    if (task == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("library-detail-empty")
                .semantics { contentDescription = "Select a library task" },
            contentAlignment = Alignment.Center,
        ) {
            Text("Select a task")
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("library-detail")
            .semantics { contentDescription = "Task details for ${task.title}" }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(task.title.ifBlank { task.taskId }, style = MaterialTheme.typography.headlineMedium)
        Text("Status: ${task.status.ifBlank { "UNKNOWN" }}")
        task.message?.takeIf { it.isNotBlank() }?.let { Text("Error: $it") }

        when {
            task.canOpenReader && task.canExport -> {
                Button(
                    onClick = { onOpenReader(task.taskId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .testTag("library-open-reader-${task.taskId}")
                        .semantics { contentDescription = "Open reader for ${task.title}" },
                ) { Text("Open reader") }
                Button(
                    onClick = { onExport(task.taskId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .testTag("library-export-${task.taskId}")
                        .semantics { contentDescription = "Export ${task.title}" },
                ) { Text("Export") }
            }

            task.canRegenerate -> {
                Text("This task failed and can be retried.")
                Button(
                    onClick = { onRegenerate(task.taskId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .testTag("library-regenerate-${task.taskId}")
                        .semantics { contentDescription = "Regenerate ${task.title}" },
                ) { Text("Regenerate") }
            }

            else -> Text("Task is still processing.")
        }
    }
}
