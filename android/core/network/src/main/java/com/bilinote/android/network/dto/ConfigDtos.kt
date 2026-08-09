package com.bilinote.android.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProviderDto(
    val id: String? = null,
    val name: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    val type: String? = null,
    val enabled: Int? = null,
)

@Serializable
data class ModelDto(
    val id: Int? = null,
    @SerialName("provider_id") val providerId: String? = null,
    @SerialName("model_name") val modelName: String? = null,
)

@Serializable
data class HealthDto(
    val status: String? = null,
    val message: String? = null,
    val ready: Boolean? = null,
    val reason: String? = null,
    @SerialName("transcriber_type") val transcriberType: String? = null,
    @SerialName("model_size") val modelSize: String? = null,
    val downloading: Boolean? = null,
    val backend: String? = null,
    val ffmpeg: String? = null,
    val db: String? = null,
    @SerialName("whisper_model") val whisperModel: WhisperModelHealthDto? = null,
)

@Serializable
data class WhisperModelHealthDto(
    val size: String? = null,
    val type: String? = null,
    val downloaded: Boolean? = null,
    val checked: JsonElement? = null,
)
