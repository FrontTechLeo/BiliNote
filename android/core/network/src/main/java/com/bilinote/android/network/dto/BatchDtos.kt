package com.bilinote.android.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BatchAppendPreviewDto(@SerialName("source_url") val sourceUrl: String? = null)

typealias BatchPreviewRequest = BatchAppendPreviewDto

@Serializable
data class BatchPreviewItem(
    val id: String? = null,
    val title: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val platform: String? = null,
    val position: Int? = null,
)

@Serializable
data class BatchPreview(
    @SerialName("preview_id") val previewId: String? = null,
    val title: String? = null,
    val items: List<BatchPreviewItem>? = null,
    @SerialName("discovered_count") val discoveredCount: Int? = null,
    @SerialName("skipped_count") val skippedCount: Int? = null,
    @SerialName("duplicate_count") val duplicateCount: Int? = null,
)

@Serializable
data class BatchConfig(
    val quality: String? = null,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("provider_id") val providerId: String? = null,
    val screenshot: Boolean? = null,
    val link: Boolean? = null,
    val format: List<String>? = null,
    val style: String? = null,
    val extras: String? = null,
    @SerialName("video_understanding") val videoUnderstanding: Boolean? = null,
    @SerialName("video_interval") val videoInterval: Int? = null,
    @SerialName("grid_size") val gridSize: List<Int>? = null,
)

@Serializable
data class BatchCreate(
    val title: String? = null,
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("source_value") val sourceValue: String? = null,
    val config: BatchConfig? = null,
    val items: List<BatchPreviewItem>? = null,
)

@Serializable
data class BatchUpdate(val title: String? = null, val config: BatchConfig? = null)

@Serializable
data class BatchTask(
    val id: String? = null,
    val title: String? = null,
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("source_value") val sourceValue: String? = null,
    val status: String? = null,
    @SerialName("total_count") val totalCount: Int? = null,
    @SerialName("completed_count") val completedCount: Int? = null,
    @SerialName("failed_count") val failedCount: Int? = null,
    @SerialName("running_count") val runningCount: Int? = null,
    val progress: Double? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val config: BatchConfig? = null,
)

@Serializable
data class BatchTaskPageDto(
    val items: List<BatchTask>? = null,
    val total: Int? = null,
    val page: Int? = null,
    @SerialName("page_size") val pageSize: Int? = null,
)

@Serializable
data class BatchItem(
    val id: String? = null,
    @SerialName("batch_id") val batchId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    val title: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val platform: String? = null,
    val position: Int? = null,
    val status: String? = null,
    val stage: String? = null,
    val message: String? = null,
    val attempts: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class BatchEvent(
    val id: Int? = null,
    val status: String? = null,
    val message: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class BatchAppend(val items: List<BatchPreviewItem>? = null)

@Serializable
data class BatchAppendResultDto(
    val task: BatchTask? = null,
    @SerialName("added_items") val addedItems: List<BatchItem>? = null,
    @SerialName("added_count") val addedCount: Int? = null,
    @SerialName("skipped_count") val skippedCount: Int? = null,
)

@Serializable
data class BatchDeleteRequest(@SerialName("item_ids") val itemIds: List<String>)

@Serializable
data class BatchDeleteResultDto(
    val task: BatchTask? = null,
    @SerialName("deleted_count") val deletedCount: Int? = null,
    val cleanup: JsonElement? = null,
)

@Serializable
data class BatchExportRequest(@SerialName("item_ids") val itemIds: List<String>? = null)
