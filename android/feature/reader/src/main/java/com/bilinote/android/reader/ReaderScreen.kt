package com.bilinote.android.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    windowWidthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    onExport: (ReaderExport) -> Unit,
    onOpenTimeLink: (Int) -> Unit = {},
    relatedTasks: List<com.bilinote.android.database.entity.TaskEntity> = emptyList(),
    onSelectRelatedTask: (com.bilinote.android.database.entity.TaskEntity) -> Unit = {},
    accessToken: String? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val progress by viewModel.progress.collectAsState()
    LaunchedEffect(uiState.exportState) {
        val ready = uiState.exportState as? ReaderExportState.Ready ?: return@LaunchedEffect
        onExport(ready.export)
        viewModel.clearExportState()
    }
    val content: @Composable () -> Unit = {
        ReaderPane(
            uiState = uiState,
            progress = progress,
            progressStore = viewModel.progressStoreForUi,
            onBack = onBack,
            onOpenSource = onOpenSource,
            onExport = viewModel::export,
            onOpenTimeLink = onOpenTimeLink,
            onRetry = viewModel::load,
            accessToken = accessToken,
        )
    }

    Box(Modifier.fillMaxSize().testTag("reader-screen")) {
        val readerContent: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.fillMaxSize().widthIn(max = 720.dp)) { content() }
            }
        }
        if (windowWidthSizeClass == WindowWidthSizeClass.Compact || relatedTasks.isEmpty()) {
            readerContent()
        } else {
            Row(Modifier.fillMaxSize()) {
                RelatedTasksPane(
                    tasks = relatedTasks,
                    onSelectTask = onSelectRelatedTask,
                    modifier = Modifier.fillMaxHeight().weight(
                        if (windowWidthSizeClass == WindowWidthSizeClass.Medium) 0.38f else 0.30f,
                    ),
                )
                Box(
                    modifier = Modifier.fillMaxHeight().weight(
                        if (windowWidthSizeClass == WindowWidthSizeClass.Medium) 0.62f else 0.70f,
                    ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = 720.dp),
                    ) { content() }
                }
            }
        }
    }
}

@Composable
private fun ReaderPane(
    uiState: ReaderUiState,
    progress: com.bilinote.android.database.entity.ReadingProgressEntity?,
    progressStore: ProgressStore,
    accessToken: String?,
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    onExport: () -> Unit,
    onOpenTimeLink: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag("reader-pane")) {
        ReaderToolbar(
            task = (uiState.loadState as? ReaderLoadState.Success)?.task,
            onBack = onBack,
            onOpenSource = onOpenSource,
            onExport = onExport,
        )
        HorizontalDivider()
        when (val state = uiState.loadState) {
            ReaderLoadState.Loading -> StatusPane("正在加载笔记…", showProgress = true)
            is ReaderLoadState.Error -> StatusPane(state.message, showProgress = false, onRetry = onRetry)
            is ReaderLoadState.Success -> ReaderMarkdownContent(
                task = state.task,
                progress = progress,
                progressStore = progressStore,
                onOpenTimeLink = onOpenTimeLink,
                accessToken = accessToken,
            )
        }
    }
}

@Composable
private fun ReaderToolbar(
    task: com.bilinote.android.database.entity.TaskEntity?,
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("reader-back")
                .semantics { contentDescription = "返回阅读列表" },
        ) { Text("返回") }
        Text(
            text = task?.let { it.title.ifBlank { it.taskId } } ?: "阅读器",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
        )
        if (task != null && task.sourceUrl.isNotBlank()) {
            TextButton(
                onClick = { onOpenSource(task.sourceUrl) },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("reader-source")
                    .semantics { contentDescription = "打开源视频" },
            ) { Text("源链接") }
        }
        if (task != null) {
            TextButton(
                onClick = onExport,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("reader-export")
                    .semantics { contentDescription = "导出笔记" },
            ) { Text("导出") }
        }
    }
}

@Composable
private fun StatusPane(message: String, showProgress: Boolean, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag("reader-status"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showProgress) CircularProgressIndicator()
        Text(message, modifier = Modifier.padding(top = 12.dp))
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("reader-retry")
                    .semantics { contentDescription = "重试加载笔记" },
            ) { Text("重试") }
        }
    }
}

@Composable
@OptIn(FlowPreview::class)
private fun ReaderMarkdownContent(
    task: com.bilinote.android.database.entity.TaskEntity,
    progress: com.bilinote.android.database.entity.ReadingProgressEntity?,
    progressStore: ProgressStore,
    onOpenTimeLink: (Int) -> Unit,
    accessToken: String?,
) {
    val scrollState = rememberScrollState()
    var measured by remember(task.taskId, task.updatedAt) { mutableStateOf(false) }
    var restored by remember(task.taskId, task.updatedAt, progress?.scrollOffset) { mutableStateOf(false) }
    val initialOffset = progress?.scrollOffset ?: 0

    LaunchedEffect(task.taskId, task.serviceBaseUrl, initialOffset, measured) {
        if (measured) {
            scrollState.scrollTo(initialOffset.coerceIn(0, scrollState.maxValue))
            restored = true
        }
    }

    LaunchedEffect(task.taskId, task.serviceBaseUrl) {
        try {
            snapshotFlow { scrollState.value }
                .sample(180)
                .collectLatest { offset ->
                    progressStore.saveThrottled(
                        task.progress(offset, scrollState.maxValue),
                    )
                }
        } finally {
            withContext(NonCancellable) {
                progressStore.saveNow(task.progress(scrollState.value, scrollState.maxValue))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .onGloballyPositioned { measured = true }
            .testTag("reader-content")
            .then(if (restored) Modifier.testTag("reader-content-restored") else Modifier),
    ) {
        MarkdownRenderer(
            markdown = task.markdown.orEmpty(),
            backendUrl = task.serviceBaseUrl,
            accessToken = accessToken,
            modifier = Modifier.fillMaxWidth(),
            onOpenTimeLink = onOpenTimeLink,
        )
    }
}

private fun com.bilinote.android.database.entity.TaskEntity.progress(
    offset: Int,
    maxOffset: Int,
) = com.bilinote.android.database.entity.ReadingProgressEntity(
    taskId = taskId,
    serviceBaseUrl = serviceBaseUrl,
    scrollOffset = offset,
    percent = if (maxOffset <= 0) 0.0 else (offset.toDouble() / maxOffset).coerceIn(0.0, 1.0),
    updatedAt = System.currentTimeMillis(),
)

@Composable
private fun RelatedTasksPane(
    tasks: List<com.bilinote.android.database.entity.TaskEntity>,
    onSelectTask: (com.bilinote.android.database.entity.TaskEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(12.dp).testTag("reader-related-tasks"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("相关任务", style = MaterialTheme.typography.titleMedium)
        tasks.forEach { task ->
            TextButton(
                onClick = { onSelectTask(task) },
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                    .testTag("reader-related-${task.taskId}")
                    .semantics { contentDescription = "打开相关任务 ${task.title.ifBlank { task.taskId }}" },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(task.title.ifBlank { task.taskId }, maxLines = 2)
                    Text(task.status.ifBlank { "UNKNOWN" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
