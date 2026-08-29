package com.bilinote.android.feature.batch

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bilinote.android.core.designsystem.ImageCompressionLevel
import com.bilinote.android.core.designsystem.ImageCompressionSelector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchCreateSheet(
    viewModel: BatchViewModel,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sourceMode by remember { mutableStateOf("bilibili") }
    var sourceUrl by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var providerId by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var extras by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(defaultBatchConfig.quality) }
    var style by remember { mutableStateOf(defaultBatchConfig.style) }
    var videoUnderstanding by remember { mutableStateOf(defaultBatchConfig.videoUnderstanding) }
    var videoInterval by remember { mutableStateOf(defaultBatchConfig.videoInterval.toString()) }
    var gridSize by remember { mutableStateOf("2 x 2") }
    var imageCompression by remember { mutableStateOf(defaultBatchConfig.imageCompression) }
    var formats by remember { mutableStateOf(defaultBatchConfig.format.toSet()) }
    var link by remember { mutableStateOf(defaultBatchConfig.link) }
    var screenshot by remember { mutableStateOf(defaultBatchConfig.screenshot) }
    var files by remember { mutableStateOf<List<LocalBatchFile>>(emptyList()) }
    var wasCreating by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        files = uris.map { uri ->
            LocalBatchFile(uri, displayName(context.contentResolver, uri))
        }
    }

    fun switchSource(nextMode: String) {
        if (sourceMode == nextMode) return
        viewModel.clearPreview()
        sourceMode = nextMode
        sourceUrl = ""
        files = emptyList()
    }

    fun updateFormat(format: String, checked: Boolean) {
        formats = if (checked) formats + format else formats - format
        when (format) {
            "link" -> link = checked
            "screenshot" -> screenshot = checked && videoUnderstanding
        }
    }

    LaunchedEffect(state.isCreating) {
        if (wasCreating && !state.isCreating && state.actionError == null && state.selectedTask != null) {
            onDismissRequest()
        }
        wasCreating = state.isCreating
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = Modifier.testTag("batch-create-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("新建合集", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { switchSource("bilibili") },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Text("B站链接") }
                OutlinedButton(
                    onClick = { switchSource("local") },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Text("本地文件") }
            }
            if (sourceMode == "bilibili") {
                OutlinedTextField(
                    value = sourceUrl,
                    onValueChange = { sourceUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("B站链接") },
                    singleLine = true,
                )
                Button(
                    onClick = { viewModel.previewBilibili(sourceUrl) },
                    enabled = sourceUrl.isNotBlank() && !state.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .testTag("batch-preview-bilibili"),
                ) { Text(if (state.isLoading) "预览中…" else "预览") }
            } else {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("video/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .testTag("batch-pick-files"),
                ) { Text("选择多个本地视频") }
                files.forEach { file -> Text("已选：${file.name}") }
                Button(
                    onClick = { viewModel.previewLocal(files.map { it.part(context.contentResolver) }) },
                    enabled = files.isNotEmpty() && !state.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .testTag("batch-preview-local"),
                ) { Text(if (state.isLoading) "预览中…" else "预览") }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标题") },
                singleLine = true,
            )
            OutlinedTextField(
                value = providerId,
                onValueChange = { providerId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("providerId") },
                singleLine = true,
            )
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("modelName") },
                singleLine = true,
            )
            if (providerId.isBlank() || modelName.isBlank()) {
                Text(
                    text = "providerId 和 modelName 为必填项",
                    modifier = Modifier.testTag("batch-config-required"),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = quality,
                onValueChange = { quality = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("quality") },
                singleLine = true,
            )
            OutlinedTextField(
                value = style,
                onValueChange = { style = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("style") },
                singleLine = true,
            )
            SwitchRow("videoUnderstanding", videoUnderstanding) {
                videoUnderstanding = it
                if (!it) {
                    screenshot = false
                    formats = formats - "screenshot"
                }
            }
            SwitchRow("link", link) {
                link = it
                formats = if (it) formats + "link" else formats - "link"
            }
            SwitchRow("screenshot", screenshot, enabled = videoUnderstanding) {
                screenshot = it
                formats = if (it) formats + "screenshot" else formats - "screenshot"
            }
            OutlinedTextField(
                value = videoInterval,
                onValueChange = { videoInterval = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("videoInterval") },
                singleLine = true,
            )
            OutlinedTextField(
                value = gridSize,
                onValueChange = { gridSize = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("gridSize，例如 2 x 2") },
                singleLine = true,
            )
            ImageCompressionSelector(
                value = imageCompression,
                onValueChange = { imageCompression = it },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = extras,
                onValueChange = { extras = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("extras") },
                minLines = 2,
            )
            Text("format")
            listOf("toc", "summary", "link", "screenshot").forEach { format ->
                FormatRow(
                    label = format,
                    checked = format in formats,
                    enabled = format != "screenshot" || videoUnderstanding,
                    onCheckedChange = { checked -> updateFormat(format, checked) },
                )
            }

            state.preview?.let { preview ->
                Text("预览：${preview.items.size} 项")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = viewModel::selectAll,
                        modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("batch-select-all"),
                    ) { Text("全选") }
                    TextButton(
                        onClick = viewModel::clearSelection,
                        modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("batch-clear-selection"),
                    ) { Text("清空") }
                }
                preview.items.forEachIndexed { index, item ->
                    val selectionKey = viewModel.previewSelectionKey(item, index)
                    if (selectionKey.isNotBlank()) {
                        FormatRow(
                            label = item.title?.ifBlank { null } ?: item.sourceUrl ?: selectionKey,
                            checked = selectionKey in state.selectedPreviewIds,
                            onCheckedChange = { viewModel.togglePreviewSelection(selectionKey) },
                        )
                    }
                }
            }
            state.actionError?.let { Text("操作失败：$it", color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val grid = parseGridSize(gridSize)
                    val config = BatchConfig(
                        quality = quality,
                        providerId = providerId,
                        modelName = modelName,
                        style = style,
                        extras = extras,
                        videoUnderstanding = videoUnderstanding,
                        videoInterval = videoInterval.toIntOrNull()?.coerceAtLeast(1) ?: defaultBatchConfig.videoInterval,
                        gridSize = grid,
                        imageCompression = imageCompression,
                        format = formats.toList().filter { it != "screenshot" || videoUnderstanding },
                        linkOverride = formats.contains("link"),
                        screenshotOverride = formats.contains("screenshot") && videoUnderstanding,
                    )
                    viewModel.createBatch(
                        title = title,
                        sourceType = sourceMode,
                        sourceValue = if (sourceMode == "bilibili") sourceUrl else "local",
                        config = config,
                    )
                },
                enabled = state.selectedPreviewIds.isNotEmpty() &&
                    providerId.isNotBlank() &&
                    modelName.isNotBlank() &&
                    !state.isCreating,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .testTag("batch-create-submit"),
            ) { Text(if (state.isCreating) "创建中…" else "创建合集") }
            OutlinedButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp),
            ) { Text("取消") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Switch
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun FormatRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Checkbox
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label)
    }
}

private data class LocalBatchFile(val uri: Uri, val name: String) {
    fun part(contentResolver: ContentResolver): MultipartBody.Part {
        val mimeType = contentResolver.getType(uri) ?: "video/mp4"
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } else -1L
        } ?: -1L
        val body = object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()
            override fun contentLength(): Long = size
            override fun writeTo(sink: BufferedSink) {
                val input = contentResolver.openInputStream(uri) ?: throw IOException("无法打开本地文件")
                input.use { sink.writeAll(it.source()) }
            }
        }
        return MultipartBody.Part.createFormData("files", name, body)
    }
}

private fun displayName(contentResolver: ContentResolver, uri: Uri): String =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getStringOrNull(OpenableColumns.DISPLAY_NAME) else null
    }?.takeUnless(String::isBlank) ?: uri.lastPathSegment ?: "video.mp4"

private fun Cursor.getStringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun parseGridSize(value: String): List<Int> {
    val values = Regex("\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
    return if (values.size >= 2) {
        listOf(values[0].coerceAtLeast(1), values[1].coerceAtLeast(1))
    } else {
        defaultBatchConfig.gridSize
    }
}
