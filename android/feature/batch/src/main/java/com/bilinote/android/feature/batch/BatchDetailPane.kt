package com.bilinote.android.feature.batch

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bilinote.android.core.designsystem.ImageCompressionSelector

@Composable
fun BatchDetailPane(
    uiState: BatchUiState,
    onRefresh: () -> Unit,
    onUpdate: (title: String, config: BatchConfig) -> Unit,
    onExport: (batchId: String?, itemIds: List<String>?) -> Unit,
    onExportItem: (itemId: String) -> Unit,
    onOpenReader: (taskId: String) -> Unit,
    onRetry: (itemId: String) -> Unit,
    onDelete: (itemId: String) -> Unit,
    onLoadEvents: (itemId: String) -> Unit,
    showItems: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val task = uiState.selectedTask
    if (task == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .testTag("batch-detail-empty"),
            contentAlignment = Alignment.Center,
        ) { Text("请选择一个合集") }
        return
    }

    var editing by remember(task.id) { mutableStateOf(false) }
    val selectedItem = uiState.items.firstOrNull { it.id == (uiState.eventsItemId ?: uiState.selectedItemId) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("batch-detail")
            .semantics { contentDescription = "合集详情 ${task.title ?: task.id}" },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.title?.ifBlank { null } ?: task.id.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                    Text("状态：${batchStatusLabel(task.status)}")
                }
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("batch-detail-refresh"),
                ) { Text("刷新") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { editing = true },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("batch-edit"),
                ) { Text("编辑") }
                Button(
                    onClick = { onExport(task.id, null) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("batch-export"),
                ) { Text("导出合集") }
            }
        }
        item {
            Text("进度摘要", style = MaterialTheme.typography.titleMedium)
            Text("已完成 ${task.completedCount} / ${task.totalCount} 项 · 失败 ${task.failedCount} · 处理中 ${task.runningCount}")
            LinearProgressIndicator(
                progress = { task.progress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().testTag("batch-progress"),
            )
        }
        item {
            Text("配置摘要", style = MaterialTheme.typography.titleMedium)
            Text(
                "质量 ${task.config?.quality ?: "未知"} · 风格 ${task.config?.style ?: "未知"} · " +
                    "格式 ${task.config?.format?.joinToString().orEmpty().ifBlank { "默认" }}",
            )
        }

        if (showItems) {
            item { Text("子任务", style = MaterialTheme.typography.titleMedium) }
            items(
                items = uiState.items,
                key = { it.id ?: "item-${it.position}-${it.taskId}" },
            ) { item ->
                BatchItemRow(
                    item = item,
                    onOpenReader = { item.taskId?.let(onOpenReader) },
                    onExport = { item.id?.let(onExportItem) },
                    onRetry = { item.id?.let(onRetry) },
                    onDelete = { item.id?.let(onDelete) },
                    onLoadEvents = { item.id?.let(onLoadEvents) },
                )
            }
        }
        item {
            if (!showItems || selectedItem != null) {
                if (selectedItem == null) {
                    Text("日志", style = MaterialTheme.typography.titleMedium)
                    Text("请选择一个子任务查看日志")
                } else {
                    Text("日志：${selectedItem.title ?: selectedItem.id}", style = MaterialTheme.typography.titleMedium)
                    if (uiState.events.isEmpty()) {
                        Text("暂无日志")
                    } else {
                        uiState.events.forEach { event ->
                            Text("${event.createdAt.orEmpty()} · ${event.status.orEmpty()} · ${event.message.orEmpty()}")
                        }
                    }
                }
            } else {
                Text("点击子任务查看日志")
            }
        }
        item {
            uiState.actionError?.let { Text("操作失败：$it", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (editing) {
        BatchEditDialog(
            task = task,
            onDismiss = { editing = false },
            onSave = { title, config ->
                onUpdate(title, config)
                editing = false
            },
        )
    }
}

@Composable
fun BatchSubtaskPane(
    uiState: BatchUiState,
    onOpenReader: (taskId: String) -> Unit,
    onExportItem: (itemId: String) -> Unit,
    onRetry: (itemId: String) -> Unit,
    onDelete: (itemId: String) -> Unit,
    onLoadEvents: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).testTag("batch-subtask-list"),
    ) {
        Text("子任务", style = MaterialTheme.typography.titleLarge)
        if (uiState.items.isEmpty()) {
            Text("暂无子任务")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.items, key = { it.id ?: "item-${it.position}" }) { item ->
                    BatchItemRow(
                        item = item,
                        onOpenReader = { item.taskId?.let(onOpenReader) },
                        onExport = { item.id?.let(onExportItem) },
                        onRetry = { item.id?.let(onRetry) },
                        onDelete = { item.id?.let(onDelete) },
                        onLoadEvents = { item.id?.let(onLoadEvents) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchItemRow(
    item: BatchItem,
    onOpenReader: () -> Unit,
    onExport: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onLoadEvents: () -> Unit,
) {
    val status = item.status?.trim()?.uppercase()
    val success = status in setOf("SUCCESS", "DONE", "COMPLETED") && !item.taskId.isNullOrBlank()
    val failed = status in setOf("FAILED", "ERROR", "PARTIAL_FAILED", "CANCELLED")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("batch-item-${item.id.orEmpty()}")
            .semantics { contentDescription = "子任务 ${item.title ?: item.id}，${batchStatusLabel(item.status)}" }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${item.position ?: "-"}. ${item.title ?: item.sourceUrl ?: item.id.orEmpty()}")
        Text("状态：${batchStatusLabel(item.status)}${item.stage?.let { " · $it" }.orEmpty()}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onLoadEvents,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) { Text("日志") }
            if (success) {
                Button(
                    onClick = onOpenReader,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("batch-open-reader-${item.id}"),
                ) { Text("阅读") }
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Text("导出") }
            } else if (failed) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Text("重试") }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Text("删除") }
            }
        }
        item.message?.takeIf(String::isNotBlank)?.let { Text("说明：$it") }
        HorizontalDivider()
    }
}

@Composable
private fun BatchEditDialog(
    task: BatchTask,
    onDismiss: () -> Unit,
    onSave: (title: String, config: BatchConfig) -> Unit,
) {
    var title by remember(task.id, task.title) { mutableStateOf(task.title.orEmpty()) }
    val original = task.config ?: defaultBatchConfig
    var providerId by remember(task.id, original.providerId) { mutableStateOf(original.providerId) }
    var modelName by remember(task.id, original.modelName) { mutableStateOf(original.modelName) }
    var quality by remember(task.id, original.quality) { mutableStateOf(original.quality) }
    var style by remember(task.id, original.style) { mutableStateOf(original.style) }
    var extras by remember(task.id, original.extras) { mutableStateOf(original.extras) }
    var videoInterval by remember(task.id, original.videoInterval) { mutableStateOf(original.videoInterval.toString()) }
    var gridSize by remember(task.id, original.gridSize) { mutableStateOf(original.gridSize.joinToString(" x ")) }
    var imageCompression by remember(task.id, original.imageCompression) { mutableStateOf(original.imageCompression) }
    var format by remember(task.id, original.format) { mutableStateOf(original.format.joinToString(",")) }
    var videoUnderstanding by remember(task.id, original.videoUnderstanding) { mutableStateOf(original.videoUnderstanding) }
    var link by remember(task.id, original.link) { mutableStateOf(original.link) }
    var screenshot by remember(task.id, original.screenshot) { mutableStateOf(original.screenshot) }

    fun updateFormat(next: String) {
        format = next
        val values = next.split(",").map(String::trim).filter(String::isNotEmpty).toSet()
        link = "link" in values
        screenshot = "screenshot" in values && videoUnderstanding
    }

    fun updateLink(next: Boolean) {
        link = next
        val values = format.split(",").map(String::trim).filter(String::isNotEmpty).toMutableSet()
        if (next) values += "link" else values -= "link"
        format = values.joinToString(",")
    }

    fun updateScreenshot(next: Boolean) {
        screenshot = next && videoUnderstanding
        val values = format.split(",").map(String::trim).filter(String::isNotEmpty).toMutableSet()
        if (screenshot) values += "screenshot" else values -= "screenshot"
        format = values.joinToString(",")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑合集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true)
                OutlinedTextField(providerId, { providerId = it }, label = { Text("providerId") }, singleLine = true)
                OutlinedTextField(modelName, { modelName = it }, label = { Text("modelName") }, singleLine = true)
                OutlinedTextField(quality, { quality = it }, label = { Text("quality") }, singleLine = true)
                OutlinedTextField(style, { style = it }, label = { Text("style") }, singleLine = true)
                OutlinedTextField(extras, { extras = it }, label = { Text("extras") }, minLines = 2)
                OutlinedTextField(videoInterval, { videoInterval = it.filter(Char::isDigit) }, label = { Text("videoInterval") }, singleLine = true)
                OutlinedTextField(gridSize, { gridSize = it }, label = { Text("gridSize") }, singleLine = true)
                ImageCompressionSelector(
                    value = imageCompression,
                    onValueChange = { imageCompression = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(format, { updateFormat(it) }, label = { Text("format，逗号分隔") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = videoUnderstanding,
                        onCheckedChange = {
                            videoUnderstanding = it
                            if (!it) updateScreenshot(false)
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "videoUnderstanding"
                            role = Role.Checkbox
                        },
                    )
                    Text("videoUnderstanding")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = link,
                        onCheckedChange = { updateLink(it) },
                        modifier = Modifier.semantics {
                            contentDescription = "link"
                            role = Role.Checkbox
                        },
                    )
                    Text("link")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = screenshot,
                        onCheckedChange = { updateScreenshot(it) },
                        enabled = videoUnderstanding,
                        modifier = Modifier.semantics {
                            contentDescription = "screenshot"
                            role = Role.Checkbox
                        },
                    )
                    Text("screenshot")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        title,
                        original.copy(
                            providerId = providerId,
                            modelName = modelName,
                            quality = quality,
                            style = style,
                            extras = extras,
                            videoInterval = videoInterval.toIntOrNull()?.coerceAtLeast(1) ?: original.videoInterval,
                            gridSize = parseGridSize(gridSize, original.gridSize),
                            imageCompression = imageCompression,
                            format = format.split(",").map(String::trim).filter(String::isNotEmpty)
                                .filter { it != "screenshot" || videoUnderstanding },
                            linkOverride = link,
                            screenshotOverride = screenshot && videoUnderstanding,
                            videoUnderstanding = videoUnderstanding,
                        ),
                    )
                },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") }
        },
    )
}

private fun parseGridSize(value: String, fallback: List<Int>): List<Int> {
    val values = Regex("\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
    return if (values.size >= 2) listOf(values[0].coerceAtLeast(1), values[1].coerceAtLeast(1)) else fallback
}
