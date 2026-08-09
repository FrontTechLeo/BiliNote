package com.bilinote.android.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilinote.android.core.designsystem.AdaptiveListDetailPane
import com.bilinote.android.core.designsystem.BiliNoteShapes
import com.bilinote.android.core.designsystem.BiliNoteThemeColors
import com.bilinote.android.core.designsystem.BiliNoteThemeSpacing

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    windowWidthSizeClass: WindowWidthSizeClass,
    onOpenReader: (taskId: String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var isComposerVisible by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onScreenStarted()
                Lifecycle.Event.ON_STOP -> viewModel.onScreenStopped()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenStopped()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.navigationEvents.collect { navigation ->
            onOpenReader(navigation.taskId)
        }
    }

    val openReader: (String) -> Unit = remember(onOpenReader) { { taskId -> onOpenReader(taskId) } }
    val retry: () -> Unit = remember(viewModel) { { viewModel.retryPolling() } }

    if (windowWidthSizeClass == WindowWidthSizeClass.Compact) {
        HomeCompactLayout(
            uiState = uiState,
            onCreate = { isComposerVisible = true },
            onOpenReader = openReader,
            onRetry = retry,
        )
    } else {
        AdaptiveListDetailPane(
            windowWidthSizeClass = windowWidthSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .testTag(
                    if (windowWidthSizeClass == WindowWidthSizeClass.Expanded) {
                        "home-expanded-layout"
                    } else {
                        "home-medium-layout"
                    },
                ),
            content = {
                HomeCompactLayout(
                    uiState = uiState,
                    onCreate = { isComposerVisible = true },
                    onOpenReader = openReader,
                    onRetry = retry,
                )
            },
            listContent = {
                HomeWidePrimaryColumn(
                    uiState = uiState,
                    onCreate = { isComposerVisible = true },
                    onOpenReader = openReader,
                    onRetry = retry,
                )
            },
            detailContent = {
                HomeWideRecentColumn(
                    tasks = uiState.recentTasks,
                    onOpenReader = openReader,
                    onRetry = retry,
                )
            },
        )
    }

    if (isComposerVisible) {
        NoteComposerSheet(
            viewModel = viewModel,
            onDismissRequest = { isComposerVisible = false },
        )
    }
}

@Composable
private fun HomeCompactLayout(
    uiState: HomeUiState,
    onCreate: () -> Unit,
    onOpenReader: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = BiliNoteThemeSpacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home-compact-layout")
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        HomeHero(uiState.workflow, onCreate)
        ActiveTaskSection(uiState.activeTasks, onOpenReader, onRetry)
        ContinueReadingSection(uiState.recentTasks, onOpenReader)
        RecentNotesSection(uiState.recentTasks, onOpenReader, onRetry)
    }
}

@Composable
private fun HomeWidePrimaryColumn(
    uiState: HomeUiState,
    onCreate: () -> Unit,
    onOpenReader: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = BiliNoteThemeSpacing
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        HomeHero(uiState.workflow, onCreate)
        ActiveTaskSection(uiState.activeTasks, onOpenReader, onRetry)
        ContinueReadingSection(uiState.recentTasks, onOpenReader)
    }
}

@Composable
private fun HomeWideRecentColumn(
    tasks: List<HomeTask>,
    onOpenReader: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = BiliNoteThemeSpacing
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md)
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        RecentNotesSection(tasks, onOpenReader, onRetry)
    }
}

@Composable
private fun HomeHero(workflow: HomeWorkflowState, onCreate: () -> Unit) {
    val spacing = BiliNoteThemeSpacing
    val colors = BiliNoteThemeColors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = BiliNoteShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text("把视频变成可读笔记", style = MaterialTheme.typography.headlineMedium)
            Text(
                "粘贴视频链接，或从设备选择一个视频开始。",
                style = MaterialTheme.typography.bodyLarge,
            )
            workflowMessage(workflow)?.let { message ->
                Text(
                    message,
                    color = if (workflow is HomeWorkflowState.Failure) {
                        colors.error
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .testTag("home-create-button")
                    .semantics { contentDescription = "创建笔记" },
            ) {
                Text("＋")
                Spacer(Modifier.padding(horizontal = 2.dp))
                Text("创建笔记")
            }
        }
    }
}

@Composable
private fun ActiveTaskSection(
    tasks: List<HomeTask>,
    onOpenReader: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = BiliNoteThemeSpacing
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("当前任务", style = MaterialTheme.typography.titleLarge)
        if (tasks.isEmpty()) {
            EmptyCard("暂无进行中的任务", Modifier.testTag("home-active-task-card"))
        } else {
            tasks.take(3).forEachIndexed { index, task ->
                TaskCard(
                    task = task,
                    modifier = if (index == 0) Modifier.testTag("home-active-task-card") else Modifier,
                    onOpenReader = onOpenReader,
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun ContinueReadingSection(tasks: List<HomeTask>, onOpenReader: (String) -> Unit) {
    val spacing = BiliNoteThemeSpacing
    val task = tasks.firstOrNull { it.hasResult && !it.isFailure() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home-continue-reading"),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("继续阅读", style = MaterialTheme.typography.titleLarge)
        if (task == null) {
            EmptyCard("完成的笔记会出现在这里")
        } else {
            TaskCard(task = task, onOpenReader = onOpenReader, onRetry = {})
        }
    }
}

@Composable
private fun RecentNotesSection(
    tasks: List<HomeTask>,
    onOpenReader: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = BiliNoteThemeSpacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home-recent-notes"),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("最近笔记", style = MaterialTheme.typography.titleLarge)
        if (tasks.isEmpty()) {
            EmptyCard("还没有最近笔记")
        } else {
            tasks.take(8).forEach { task ->
                TaskCard(task, onOpenReader = onOpenReader, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: HomeTask,
    modifier: Modifier = Modifier,
    onOpenReader: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = BiliNoteThemeSpacing
    val statusLabel = task.statusLabel()
    val failure = task.isFailure()
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = BiliNoteShapes.smallCard,
        border = if (failure) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(task.title.ifBlank { "未命名视频" }, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("状态：", style = MaterialTheme.typography.bodyMedium)
                Text(statusLabel, style = MaterialTheme.typography.bodyMedium)
            }
            task.message?.takeIf(String::isNotBlank)?.let { message ->
                Text(message, color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                when {
                    failure -> OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) { Text("重试") }

                    task.hasResult -> Button(
                        onClick = { onOpenReader(task.taskId) },
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) { Text("阅读") }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = BiliNoteShapes.smallCard,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(BiliNoteThemeSpacing.md),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun workflowMessage(workflow: HomeWorkflowState): String? = when (workflow) {
    HomeWorkflowState.Idle -> null
    is HomeWorkflowState.Uploading -> "正在上传视频：${workflow.percent}%"
    HomeWorkflowState.Submitting -> "正在创建任务…"
    is HomeWorkflowState.Polling -> "正在生成笔记：${workflow.status ?: "处理中"}"
    is HomeWorkflowState.Success -> "笔记已生成，可以继续阅读。"
    is HomeWorkflowState.Failure -> "创建失败：${workflow.message}"
}

private fun HomeTask.isFailure(): Boolean = when (status.trim().uppercase()) {
    "FAILED", "FAILURE", "ERROR", "CANCELLED" -> true
    else -> false
}

private fun HomeTask.statusLabel(): String = when {
    isFailure() -> "失败"
    hasResult || status.trim().uppercase() in setOf("SUCCESS", "COMPLETED", "DONE") -> "已完成"
    status.trim().uppercase() in setOf("RUNNING", "PROCESSING", "POLLING") -> "处理中"
    status.isBlank() -> "等待中"
    else -> status
}
