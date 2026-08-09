package com.bilinote.android.home

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteComposerSheet(
    viewModel: HomeViewModel,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val spacing = com.bilinote.android.core.designsystem.BiliNoteThemeSpacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var platform by remember { mutableStateOf("bilibili") }
    var videoUrl by remember { mutableStateOf("") }
    var providerId by remember { mutableStateOf("provider-1") }
    var modelName by remember { mutableStateOf("model-1") }
    var quality by remember { mutableStateOf("medium") }
    var style by remember { mutableStateOf("tutorial") }
    var videoUnderstanding by remember { mutableStateOf(false) }
    var videoInterval by remember { mutableStateOf("6") }
    var gridSize by remember { mutableStateOf("2 x 2") }
    var extras by remember { mutableStateOf("") }
    var selectedFormats by remember { mutableStateOf(setOf("toc", "summary")) }
    var localUri by remember { mutableStateOf<Uri?>(null) }
    var localName by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        localUri = uri
        localName = uri?.let { displayName(context.contentResolver, it) }
        localError = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = Modifier.testTag("note-composer-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text("创建笔记", style = MaterialTheme.typography.headlineSmall)
            Text("填写视频来源和输出偏好", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = platform,
                onValueChange = { platform = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("平台") },
                singleLine = true,
            )
            OutlinedTextField(
                value = videoUrl,
                onValueChange = { videoUrl = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "note-composer-link" },
                label = { Text("视频链接或本地文件") },
                placeholder = { Text("https://…") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = { picker.launch(arrayOf("video/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp),
            ) {
                Text(if (localName == null) "选择本地视频" else "已选择：$localName")
            }
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = providerId,
                onValueChange = { providerId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提供商") },
                singleLine = true,
            )
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型") },
                singleLine = true,
            )
            OutlinedTextField(
                value = quality,
                onValueChange = { quality = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("质量") },
                singleLine = true,
            )
            OutlinedTextField(
                value = style,
                onValueChange = { style = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("风格") },
                singleLine = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("视频理解", style = MaterialTheme.typography.titleMedium)
                    Text("允许模型分析视频画面", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = videoUnderstanding,
                    onCheckedChange = { enabled ->
                        videoUnderstanding = enabled
                        if (!enabled) selectedFormats -= "screenshot"
                    },
                )
            }
            OutlinedTextField(
                value = videoInterval,
                onValueChange = { videoInterval = it.filter { character -> character.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("视频间隔") },
                suffix = { Text("秒") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(
                value = gridSize,
                onValueChange = { gridSize = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("网格尺寸") },
                singleLine = true,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xs))
            Text("输出格式", style = MaterialTheme.typography.titleMedium)
            listOf(
                "toc" to "目录",
                "summary" to "摘要",
                "link" to "链接",
                "screenshot" to "截图",
            ).forEach { (format, label) ->
                val enabled = format != "screenshot" || videoUnderstanding
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = format in selectedFormats,
                        onCheckedChange = { checked ->
                            if (enabled) {
                                selectedFormats = if (checked) {
                                    selectedFormats + format
                                } else {
                                    selectedFormats - format
                                }
                            }
                        },
                        enabled = enabled,
                    )
                    Text(
                        text = label,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = extras,
                onValueChange = { extras = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("额外要求") },
                minLines = 3,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("取消") }
                Button(
                    onClick = {
                        val localPart = localUri?.let {
                            runCatching { createStreamingVideoPart(context.contentResolver, it) }
                                .onFailure { error -> localError = error.message ?: "无法读取本地视频" }
                                .getOrNull()
                        }
                        if (localUri != null && localPart == null) return@Button
                        viewModel.submit(
                            HomeNoteDraft(
                                videoUrl = videoUrl,
                                platform = platform,
                                providerId = providerId,
                                modelName = modelName,
                                quality = quality,
                                style = style,
                                format = selectedFormats.toList(),
                                extras = extras,
                                videoUnderstanding = videoUnderstanding,
                                videoInterval = videoInterval.toIntOrNull() ?: 6,
                                gridSize = parseGridSize(gridSize),
                                localVideo = localPart,
                            ),
                        )
                        onDismissRequest()
                    },
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("note-composer-submit"),
                ) { Text("提交") }
            }
        }
    }
}

private fun parseGridSize(value: String): List<Int> {
    val values = Regex("\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
    return if (values.size >= 2) listOf(values[0].coerceAtLeast(1), values[1].coerceAtLeast(1)) else listOf(2, 2)
}

private fun displayName(contentResolver: ContentResolver, uri: Uri): String =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getStringOrNull(OpenableColumns.DISPLAY_NAME) else null
    }?.takeUnless(String::isBlank) ?: uri.lastPathSegment ?: "video.mp4"

private fun Cursor.getStringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun createStreamingVideoPart(contentResolver: ContentResolver, uri: Uri): MultipartBody.Part {
    val name = displayName(contentResolver, uri)
    val mimeType = contentResolver.getType(uri) ?: "video/mp4"
    val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
        } else {
            -1L
        }
    } ?: -1L
    val body = object : RequestBody() {
        override fun contentType() = mimeType.toMediaTypeOrNull()

        override fun contentLength(): Long = size

        override fun writeTo(sink: BufferedSink) {
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("无法打开本地视频")
            input.use { sink.writeAll(it.source()) }
        }
    }
    return MultipartBody.Part.createFormData("file", name, body)
}
