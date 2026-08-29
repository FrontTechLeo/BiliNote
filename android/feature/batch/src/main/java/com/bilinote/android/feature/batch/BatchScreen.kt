package com.bilinote.android.feature.batch

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilinote.android.core.designsystem.AdaptiveListDetailPane

@Composable
fun BatchScreen(
    viewModel: BatchViewModel,
    windowWidthSizeClass: WindowWidthSizeClass,
    onOpenDetail: (batchId: String) -> Unit,
    onOpenReader: (taskId: String) -> Unit,
    onExport: (BatchExport) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateSheet by remember { mutableStateOf(false) }

    BatchExportEffect(uiState = uiState, onExport = onExport, clearExport = viewModel::clearExportState)
    when (windowWidthSizeClass) {
        WindowWidthSizeClass.Expanded -> {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().testTag("batch-expanded-layout")) {
            Box(Modifier.weight(0.28f).fillMaxSize()) {
                BatchListPane(
                    uiState = uiState,
                    onRefresh = viewModel::refresh,
                    onCreate = { showCreateSheet = true },
                    onSelect = { id -> viewModel.selectBatch(id); viewModel.loadDetail(id) },
                )
            }
            Box(Modifier.weight(0.30f).fillMaxSize()) {
                BatchSubtaskPane(
                    uiState = uiState,
                    onOpenReader = onOpenReader,
                    onExportItem = { itemId -> viewModel.exportItem(itemId) },
                    onRetry = { itemId -> viewModel.retryItem(itemId) },
                    onDelete = { itemId -> viewModel.deleteItem(itemId) },
                    onLoadEvents = { itemId -> viewModel.loadEvents(itemId) },
                )
            }
            Box(Modifier.weight(0.42f).fillMaxSize()) {
                BatchDetailPane(
                    uiState = uiState,
                    onRefresh = { viewModel.loadDetail() },
                    onUpdate = { title, config -> viewModel.updateBatch(title = title, config = config) },
                    onExport = { id, itemIds -> viewModel.exportBatch(id, itemIds) },
                    onExportItem = { itemId -> viewModel.exportItem(itemId) },
                    onOpenReader = onOpenReader,
                    onRetry = { itemId -> viewModel.retryItem(itemId) },
                    onDelete = { itemId -> viewModel.deleteItem(itemId) },
                    onLoadEvents = { itemId -> viewModel.loadEvents(itemId) },
                    showItems = false,
                )
            }
        }
        }

        else -> Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(
                    if (windowWidthSizeClass == WindowWidthSizeClass.Medium) {
                        "batch-medium-layout"
                    } else {
                        "batch-screen"
                    },
                )
                .semantics { contentDescription = "Batch screen" },
        ) {
            AdaptiveListDetailPane(
                windowWidthSizeClass = windowWidthSizeClass,
                modifier = Modifier.fillMaxSize(),
            content = {
                BatchListPane(
                    uiState = uiState,
                    onRefresh = viewModel::refresh,
                    onCreate = { showCreateSheet = true },
                    onSelect = { batchId ->
                        viewModel.selectBatch(batchId)
                        if (windowWidthSizeClass == WindowWidthSizeClass.Compact) {
                            onOpenDetail(batchId)
                        } else {
                            viewModel.loadDetail(batchId)
                        }
                    },
                )
            },
            listContent = {
                BatchListPane(
                    uiState = uiState,
                    onRefresh = viewModel::refresh,
                    onCreate = { showCreateSheet = true },
                    onSelect = { batchId ->
                        viewModel.selectBatch(batchId)
                        viewModel.loadDetail(batchId)
                    },
                )
            },
            detailContent = {
                BatchDetailPane(
                    uiState = uiState,
                    onRefresh = { viewModel.loadDetail() },
                    onUpdate = { title, config -> viewModel.updateBatch(title = title, config = config) },
                    onExport = { id, itemIds -> viewModel.exportBatch(id, itemIds) },
                    onExportItem = { itemId -> viewModel.exportItem(itemId) },
                    onOpenReader = onOpenReader,
                    onRetry = { itemId -> viewModel.retryItem(itemId) },
                    onDelete = { itemId -> viewModel.deleteItem(itemId) },
                    onLoadEvents = { itemId -> viewModel.loadEvents(itemId) },
                )
            },
            )
        }
    }

    if (showCreateSheet) {
        BatchCreateSheet(
            viewModel = viewModel,
            onDismissRequest = {
                viewModel.clearPreview()
                showCreateSheet = false
            },
        )
    }
}

@Composable
fun BatchDetailScreen(
    viewModel: BatchViewModel,
    batchId: String,
    onOpenReader: (taskId: String) -> Unit,
    onExport: (BatchExport) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(batchId) {
        viewModel.selectBatch(batchId)
        viewModel.loadDetail(batchId)
    }
    BatchExportEffect(uiState = uiState, onExport = onExport, clearExport = viewModel::clearExportState)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("batch-detail-screen"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) { Text("返回") }
        }
        BatchDetailPane(
            uiState = uiState,
            onRefresh = { viewModel.loadDetail() },
            onUpdate = { title, config -> viewModel.updateBatch(title = title, config = config) },
            onExport = { id, itemIds -> viewModel.exportBatch(id, itemIds) },
            onExportItem = { itemId -> viewModel.exportItem(itemId) },
            onOpenReader = onOpenReader,
            onRetry = { itemId -> viewModel.retryItem(itemId) },
            onDelete = { itemId -> viewModel.deleteItem(itemId) },
            onLoadEvents = { itemId -> viewModel.loadEvents(itemId) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BatchListPane(
    uiState: BatchUiState,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("batch-list")
            .semantics { contentDescription = "Batch collection list" }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("合集", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !uiState.isRefreshing,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("batch-refresh"),
                ) { Text(if (uiState.isRefreshing) "刷新中" else "刷新") }
                Button(
                    onClick = onCreate,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("batch-create"),
                ) { Text("新建") }
            }
        }
        uiState.syncStatus?.let { status ->
            Text(
                text = if (status.name == "Offline") {
                    "状态：离线${uiState.errorMessage?.let { "，$it" }.orEmpty()}"
                } else {
                    "状态：在线"
                },
                color = if (status.name == "Offline") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        uiState.actionError?.let { Text("操作失败：$it", color = MaterialTheme.colorScheme.error) }
        if (uiState.tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("batch-empty"),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isRefreshing) CircularProgressIndicator() else Text("暂无合集")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = uiState.tasks,
                    key = { index, task -> "${task.id ?: "batch"}-$index" },
                ) { _, task ->
                    BatchTaskRow(
                        task = task,
                        selected = task.id == uiState.selectedBatchId,
                        onClick = { task.id?.let(onSelect) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchTaskRow(task: BatchTask, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(task.title?.ifBlank { null } ?: task.id.orEmpty()) },
        supportingContent = {
            Text("状态：${batchStatusLabel(task.status)} · ${task.completedCount}/${task.totalCount}")
        },
        trailingContent = { Text("${(task.progress * 100).toInt().coerceIn(0, 100)}%") },
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 56.dp)
            .clickable(onClick = onClick)
            .testTag("batch-task-${task.id.orEmpty()}")
            .semantics { contentDescription = "合集 ${task.title ?: task.id}, ${batchStatusLabel(task.status)}" },
    )
    HorizontalDivider()
}

@Composable
private fun BatchExportEffect(
    uiState: BatchUiState,
    onExport: (BatchExport) -> Unit,
    clearExport: () -> Unit,
) {
    val exportState = uiState.exportState
    LaunchedEffect(exportState) {
        if (exportState is BatchExportState.Ready) {
            onExport(exportState.export)
            clearExport()
        }
    }
    if (exportState is BatchExportState.Exporting) {
        Text("正在准备导出…", modifier = Modifier.padding(16.dp))
    }
    if (exportState is BatchExportState.Error) {
        Text(
            text = "导出失败：${exportState.message}",
            modifier = Modifier
                .padding(16.dp)
                .testTag("batch-export-error")
                .semantics { contentDescription = "导出失败：${exportState.message}" },
            color = MaterialTheme.colorScheme.error,
        )
    }
}

internal fun batchStatusLabel(status: String?): String = when (status?.trim()?.uppercase()) {
    "SUCCESS", "DONE", "COMPLETED" -> "已完成"
    "FAILED", "ERROR", "PARTIAL_FAILED", "CANCELLED" -> "失败"
    "RUNNING", "PROCESSING" -> "处理中"
    else -> status?.ifBlank { null } ?: "未知"
}
