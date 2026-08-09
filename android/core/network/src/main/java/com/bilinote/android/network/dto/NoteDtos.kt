package com.bilinote.android.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GenerateNoteRequest(
    @SerialName("video_url") val videoUrl: String,
    val platform: String,
    val quality: String,
    val screenshot: Boolean? = null,
    val link: Boolean? = null,
    @SerialName("model_name") val modelName: String,
    @SerialName("provider_id") val providerId: String,
    @SerialName("task_id") val taskId: String? = null,
    val format: List<String>? = null,
    val style: String? = null,
    val extras: String? = null,
    @SerialName("video_understanding") val videoUnderstanding: Boolean? = null,
    @SerialName("video_interval") val videoInterval: Int? = null,
    @SerialName("grid_size") val gridSize: List<Int>? = null,
    @SerialName("prefetched_transcript") val prefetchedTranscript: PrefetchedTranscriptDto? = null,
)

@Serializable
data class PrefetchedTranscriptDto(
    val language: String? = null,
    @SerialName("full_text") val fullText: String? = null,
    val segments: List<TranscriptSegmentDto>? = null,
)

@Serializable
data class TranscriptSegmentDto(
    val start: Double? = null,
    val end: Double? = null,
    val text: String? = null,
)

@Serializable
data class GenerateNoteResponse(
    @SerialName("task_id") val taskId: String? = null,
    val status: String? = null,
    val message: String? = null,
    val result: SingleTaskResultDto? = null,
)

@Serializable
data class UploadResponse(val url: String? = null)

@Serializable
data class SingleTaskResultDto(
    val markdown: String? = null,
    @SerialName("audio_meta") val audioMeta: JsonObject? = null,
)

@Serializable
data class SingleTaskStatusDto(
    @SerialName("task_id") val taskId: String? = null,
    val status: String? = null,
    val message: String? = null,
    val result: SingleTaskResultDto? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val platform: String? = null,
)

@Serializable
data class SingleTaskHistoryDto(
    @SerialName("task_id") val taskId: String? = null,
    val title: String? = null,
    val platform: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val status: String? = null,
    val message: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("has_result") val hasResult: Boolean? = null,
    @SerialName("has_images") val hasImages: Boolean? = null,
    @SerialName("export_format") val exportFormat: String? = null,
    @SerialName("can_regenerate") val canRegenerate: Boolean? = null,
)

@Serializable
data class SingleTaskPageDto(
    val items: List<SingleTaskHistoryDto>? = null,
    val total: Int? = null,
    val page: Int? = null,
    @SerialName("page_size") val pageSize: Int? = null,
)
