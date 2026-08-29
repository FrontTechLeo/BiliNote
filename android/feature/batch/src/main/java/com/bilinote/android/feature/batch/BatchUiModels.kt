package com.bilinote.android.feature.batch

import com.bilinote.android.network.dto.BatchConfig as BatchConfigDto
import com.bilinote.android.network.dto.BatchEvent as BatchEventDto
import com.bilinote.android.network.dto.BatchItem as BatchItemDto
import com.bilinote.android.network.dto.BatchPreview as BatchPreviewDto
import com.bilinote.android.network.dto.BatchPreviewItem as BatchPreviewItemDto
import com.bilinote.android.network.dto.BatchTask as BatchTaskDto
import java.util.Locale
import com.bilinote.android.network.SyncStatus
import com.bilinote.android.core.designsystem.ImageCompressionLevel

private val DEFAULT_BATCH_FORMAT = listOf("toc", "summary")
private val DEFAULT_GRID_SIZE = listOf(2, 2)

data class BatchConfig(
    val quality: String = "medium",
    val modelName: String = "",
    val providerId: String = "",
    val format: List<String> = DEFAULT_BATCH_FORMAT,
    val linkOverride: Boolean? = null,
    val screenshotOverride: Boolean? = null,
    val style: String = "tutorial",
    val extras: String = "",
    val videoUnderstanding: Boolean = false,
    val videoInterval: Int = 6,
    val gridSize: List<Int> = DEFAULT_GRID_SIZE,
    val imageCompression: String = ImageCompressionLevel.HIGH.wireValue,
) {
    val link: Boolean get() = linkOverride ?: format.contains("link")
    val screenshot: Boolean
        get() = (screenshotOverride ?: format.contains("screenshot")) &&
            videoUnderstanding &&
            format.contains("screenshot")

    fun normalized(): BatchConfig {
        val normalizedFormat = format
            .filter { it != "screenshot" || videoUnderstanding }
            .distinct()
        return copy(
        format = normalizedFormat,
        linkOverride = normalizedFormat.contains("link"),
        screenshotOverride = normalizedFormat.contains("screenshot") && videoUnderstanding,
        gridSize = gridSize.takeIf { it.size == 2 } ?: DEFAULT_GRID_SIZE,
        imageCompression = ImageCompressionLevel.fromWireValue(imageCompression).wireValue,
        )
    }
}

val defaultBatchConfig = BatchConfig()

data class BatchPreviewItem(
    val id: String? = null,
    val title: String? = null,
    val sourceUrl: String? = null,
    val platform: String? = null,
    val position: Int? = null,
)

data class BatchPreview(
    val previewId: String? = null,
    val title: String? = null,
    val items: List<BatchPreviewItem> = emptyList(),
    val discoveredCount: Int = 0,
    val skippedCount: Int = 0,
    val duplicateCount: Int = 0,
)

data class BatchTask(
    val id: String? = null,
    val title: String? = null,
    val sourceType: String? = null,
    val sourceValue: String? = null,
    val status: String? = null,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val runningCount: Int = 0,
    val progress: Double = 0.0,
    val createdAt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val config: BatchConfig? = null,
)

data class BatchTaskPage(
    val items: List<BatchTask> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
)

data class BatchItem(
    val id: String? = null,
    val batchId: String? = null,
    val taskId: String? = null,
    val title: String? = null,
    val sourceUrl: String? = null,
    val platform: String? = null,
    val position: Int? = null,
    val status: String? = null,
    val stage: String? = null,
    val message: String? = null,
    val attempts: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

data class BatchEvent(
    val id: Int? = null,
    val status: String? = null,
    val message: String? = null,
    val createdAt: String? = null,
)

data class BatchCreateRequest(
    val title: String? = null,
    val sourceType: String? = null,
    val sourceValue: String? = null,
    val config: BatchConfig = defaultBatchConfig,
    val items: List<BatchPreviewItem> = emptyList(),
)

data class BatchUpdateRequest(
    val title: String? = null,
    val config: BatchConfig? = null,
)

data class BatchAppendRequest(val items: List<BatchPreviewItem> = emptyList())

data class BatchAppendResult(
    val task: BatchTask? = null,
    val addedItems: List<BatchItem> = emptyList(),
    val addedCount: Int = 0,
    val skippedCount: Int = 0,
)

data class BatchDeleteResult(
    val task: BatchTask? = null,
    val deletedCount: Int = 0,
)

data class BatchExport(
    val bytes: ByteArray,
    val fileName: String,
    val contentType: String?,
)

interface BatchCache {
    suspend fun loadTasks(serviceBaseUrl: String): List<BatchTask>

    suspend fun saveTasks(serviceBaseUrl: String, tasks: List<BatchTask>)
}

object NoopBatchCache : BatchCache {
    override suspend fun loadTasks(serviceBaseUrl: String): List<BatchTask> = emptyList()

    override suspend fun saveTasks(serviceBaseUrl: String, tasks: List<BatchTask>) = Unit
}

sealed interface BatchExportState {
    data object Idle : BatchExportState

    data object Exporting : BatchExportState

    data class Ready(val export: BatchExport) : BatchExportState

    data class Error(val message: String) : BatchExportState
}

data class BatchUiState(
    val tasks: List<BatchTask> = emptyList(),
    val selectedBatchId: String? = null,
    val selectedTask: BatchTask? = null,
    val items: List<BatchItem> = emptyList(),
    val selectedItemId: String? = null,
    val preview: BatchPreview? = null,
    val selectedPreviewIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isCreating: Boolean = false,
    val isAppending: Boolean = false,
    val syncStatus: SyncStatus? = null,
    val errorMessage: String? = null,
    val actionError: String? = null,
    val exportState: BatchExportState = BatchExportState.Idle,
    val events: List<BatchEvent> = emptyList(),
    val eventsItemId: String? = null,
)

fun naturalSort(values: Iterable<String>): List<String> =
    values.toList().sortedWith(Comparator(::compareNaturally))

fun <T> naturalSortBy(values: Iterable<T>, selector: (T) -> String): List<T> =
    values.toList().sortedWith { left, right -> compareNaturally(selector(left), selector(right)) }

fun filterDuplicatePreviewItems(items: Iterable<BatchPreviewItem>): List<BatchPreviewItem> {
    val seen = mutableSetOf<String>()
    return items.filter { item ->
        val sourceUrl = item.sourceUrl?.trim().orEmpty()
        sourceUrl.isEmpty() || seen.add(sourceUrl)
    }
}

private fun compareNaturally(left: String, right: String): Int {
    val leftParts = left.split(NUMERIC_BOUNDARY)
    val rightParts = right.split(NUMERIC_BOUNDARY)
    for (index in 0 until minOf(leftParts.size, rightParts.size)) {
        val leftPart = leftParts[index]
        val rightPart = rightParts[index]
        val comparison = if (leftPart.all(Char::isDigit) && rightPart.all(Char::isDigit)) {
            compareNumericParts(leftPart, rightPart)
        } else {
            leftPart.lowercase(Locale.ROOT).compareTo(rightPart.lowercase(Locale.ROOT))
        }
        if (comparison != 0) return comparison
    }
    return leftParts.size.compareTo(rightParts.size)
}

private fun compareNumericParts(left: String, right: String): Int {
    val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
    val normalizedRight = right.trimStart('0').ifEmpty { "0" }
    return normalizedLeft.length.compareTo(normalizedRight.length).takeIf { it != 0 }
        ?: normalizedLeft.compareTo(normalizedRight).takeIf { it != 0 }
        ?: 0
}

private val NUMERIC_BOUNDARY = Regex("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")

internal fun BatchConfig.toDto(): BatchConfigDto {
    val normalized = normalized()
    return BatchConfigDto(
        quality = normalized.quality,
        modelName = normalized.modelName,
        providerId = normalized.providerId,
        screenshot = normalized.screenshot,
        link = normalized.link,
        format = normalized.format,
        style = normalized.style,
        extras = normalized.extras,
        videoUnderstanding = normalized.videoUnderstanding,
        videoInterval = normalized.videoInterval,
        gridSize = normalized.gridSize,
        imageCompression = normalized.imageCompression,
    )
}

internal fun BatchPreviewItem.toDto() = BatchPreviewItemDto(
    id = id,
    title = title,
    sourceUrl = sourceUrl,
    platform = platform,
    position = position,
)

internal fun BatchPreviewItemDto.toUiModel() = BatchPreviewItem(
    id = id,
    title = title,
    sourceUrl = sourceUrl,
    platform = platform,
    position = position,
)

internal fun BatchPreviewDto.toUiModel(): BatchPreview {
    val mappedItems = items.orEmpty().map { it.toUiModel() }
    val uniqueItems = filterDuplicatePreviewItems(mappedItems)
    return BatchPreview(
        previewId = previewId,
        title = title,
        items = uniqueItems,
        discoveredCount = discoveredCount ?: 0,
        skippedCount = skippedCount ?: 0,
        duplicateCount = (duplicateCount ?: 0) + mappedItems.size - uniqueItems.size,
    )
}

internal fun BatchPreview.sortedByPosition() = copy(
    items = naturalSortBy(items) { it.position?.toString() ?: "~" },
)

internal fun BatchTaskDto.toUiModel() = BatchTask(
    id = id,
    title = title,
    sourceType = sourceType,
    sourceValue = sourceValue,
    status = status,
    totalCount = totalCount ?: 0,
    completedCount = completedCount ?: 0,
    failedCount = failedCount ?: 0,
    runningCount = runningCount ?: 0,
    progress = progress ?: 0.0,
    createdAt = createdAt,
    startedAt = startedAt,
    finishedAt = finishedAt,
    config = config?.toUiModel(),
)

internal fun BatchConfigDto.toUiModel() = BatchConfig(
    quality = quality ?: defaultBatchConfig.quality,
    modelName = modelName.orEmpty(),
    providerId = providerId.orEmpty(),
    format = format ?: defaultBatchConfig.format,
    linkOverride = link,
    screenshotOverride = screenshot,
    style = style ?: defaultBatchConfig.style,
    extras = extras.orEmpty(),
    videoUnderstanding = videoUnderstanding ?: false,
    videoInterval = videoInterval ?: defaultBatchConfig.videoInterval,
    gridSize = gridSize ?: defaultBatchConfig.gridSize,
    imageCompression = imageCompression ?: defaultBatchConfig.imageCompression,
).normalized()

internal fun BatchItemDto.toUiModel() = BatchItem(
    id = id,
    batchId = batchId,
    taskId = taskId,
    title = title,
    sourceUrl = sourceUrl,
    platform = platform,
    position = position,
    status = status,
    stage = stage,
    message = message,
    attempts = attempts ?: 0,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun BatchEventDto.toUiModel() = BatchEvent(
    id = id,
    status = status,
    message = message,
    createdAt = createdAt,
)
