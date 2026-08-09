package com.bilinote.android.network

import com.bilinote.android.network.dto.BatchAppend
import com.bilinote.android.network.dto.BatchAppendPreviewDto
import com.bilinote.android.network.dto.BatchAppendResultDto
import com.bilinote.android.network.dto.BatchCreate
import com.bilinote.android.network.dto.BatchDeleteRequest
import com.bilinote.android.network.dto.BatchDeleteResultDto
import com.bilinote.android.network.dto.BatchEvent
import com.bilinote.android.network.dto.BatchExportRequest
import com.bilinote.android.network.dto.BatchItem
import com.bilinote.android.network.dto.BatchPreview
import com.bilinote.android.network.dto.BatchTask
import com.bilinote.android.network.dto.BatchTaskPageDto
import com.bilinote.android.network.dto.BatchUpdate
import com.bilinote.android.network.dto.GenerateNoteRequest
import com.bilinote.android.network.dto.GenerateNoteResponse
import com.bilinote.android.network.dto.HealthDto
import com.bilinote.android.network.dto.ModelDto
import com.bilinote.android.network.dto.ProviderDto
import com.bilinote.android.network.dto.SingleTaskPageDto
import com.bilinote.android.network.dto.SingleTaskStatusDto
import com.bilinote.android.network.dto.UploadResponse
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

class ApiBusinessException(val code: Int, override val message: String) : RuntimeException(message)

class ApiHttpException(val statusCode: Int, override val message: String) : RuntimeException(message)

interface RetrofitBiliNoteApi {
    @POST("generate_note")
    suspend fun generateNote(@Body request: GenerateNoteRequest): Response<ApiResult<GenerateNoteResponse>>

    @Multipart
    @POST("upload")
    suspend fun upload(@Part file: MultipartBody.Part): Response<ApiResult<UploadResponse>>

    @GET("task_status/{taskId}")
    suspend fun taskStatus(@Path("taskId") taskId: String): Response<ApiResult<SingleTaskStatusDto>>

    @GET("single/tasks")
    suspend fun singleTasks(@Query("page") page: Int = 1, @Query("page_size") pageSize: Int = 50): Response<ApiResult<SingleTaskPageDto>>

    @POST("single/tasks/{taskId}/regenerate")
    suspend fun regenerate(@Path("taskId") taskId: String): Response<ApiResult<GenerateNoteResponse>>

    @GET("export_note/{taskId}")
    suspend fun exportNote(@Path("taskId") taskId: String): Response<ResponseBody>

    @POST("batch/preview/bilibili")
    suspend fun previewBilibili(@Body request: BatchAppendPreviewDto): Response<ApiResult<BatchPreview>>

    @POST("batch/tasks/{batchId}/preview/bilibili")
    suspend fun previewAppendBilibili(@Path("batchId") batchId: String, @Body request: BatchAppendPreviewDto): Response<ApiResult<BatchPreview>>

    @Multipart
    @POST("batch/preview/local")
    suspend fun previewLocal(@Part files: List<MultipartBody.Part>): Response<ApiResult<BatchPreview>>

    @Multipart
    @POST("batch/tasks/{batchId}/preview/local")
    suspend fun previewAppendLocal(@Path("batchId") batchId: String, @Part files: List<MultipartBody.Part>): Response<ApiResult<BatchPreview>>

    @GET("batch/tasks")
    suspend fun batchTasks(@Query("page") page: Int = 1, @Query("page_size") pageSize: Int = 20): Response<ApiResult<BatchTaskPageDto>>

    @POST("batch/tasks")
    suspend fun createBatch(@Body request: BatchCreate): Response<ApiResult<BatchTask>>

    @GET("batch/tasks/{batchId}")
    suspend fun batchDetail(@Path("batchId") batchId: String): Response<ApiResult<BatchTask>>

    @PATCH("batch/tasks/{batchId}")
    suspend fun updateBatch(@Path("batchId") batchId: String, @Body request: BatchUpdate): Response<ApiResult<BatchTask>>

    @POST("batch/tasks/{batchId}/items")
    suspend fun appendBatchItems(@Path("batchId") batchId: String, @Body request: BatchAppend): Response<ApiResult<BatchAppendResultDto>>

    @GET("batch/tasks/{batchId}/items")
    suspend fun batchItems(@Path("batchId") batchId: String): Response<ApiResult<List<BatchItem>>>

    @GET("batch/tasks/{batchId}/items/{itemId}/events")
    suspend fun batchEvents(@Path("batchId") batchId: String, @Path("itemId") itemId: String): Response<ApiResult<List<BatchEvent>>>

    @POST("batch/tasks/{batchId}/items/{itemId}/retry")
    suspend fun retryBatchItem(@Path("batchId") batchId: String, @Path("itemId") itemId: String): Response<ApiResult<BatchItem>>

    @DELETE("batch/tasks/{batchId}/items/{itemId}")
    suspend fun deleteBatchItem(@Path("batchId") batchId: String, @Path("itemId") itemId: String): Response<ApiResult<BatchDeleteResultDto>>

    @POST("batch/tasks/{batchId}/items/delete")
    suspend fun deleteBatchItems(@Path("batchId") batchId: String, @Body request: BatchDeleteRequest): Response<ApiResult<BatchDeleteResultDto>>

    @GET("batch/tasks/{batchId}/export")
    suspend fun exportBatch(@Path("batchId") batchId: String): Response<ResponseBody>

    @POST("batch/tasks/{batchId}/export")
    suspend fun exportBatch(@Path("batchId") batchId: String, @Body request: BatchExportRequest): Response<ResponseBody>

    @GET("batch/tasks/{batchId}/items/{itemId}/export")
    suspend fun exportBatchItem(@Path("batchId") batchId: String, @Path("itemId") itemId: String): Response<ResponseBody>

    @GET("get_all_providers")
    suspend fun providers(): Response<ApiResult<List<ProviderDto>>>

    @GET("model_list")
    suspend fun models(): Response<ApiResult<List<ModelDto>>>

    @GET("sys_health")
    suspend fun sysHealth(): Response<ApiResult<HealthDto>>
}

class BiliNoteApi(private val remote: RetrofitBiliNoteApi) {
    suspend fun taskStatus(taskId: String): SingleTaskStatusDto = remote.taskStatus(taskId).unwrap()
    suspend fun generateNote(request: GenerateNoteRequest) = remote.generateNote(request).unwrap()
    suspend fun upload(file: MultipartBody.Part) = remote.upload(file).unwrap()
    suspend fun singleTasks(page: Int = 1, pageSize: Int = 50) = remote.singleTasks(page, pageSize).unwrap()
    suspend fun regenerate(taskId: String) = remote.regenerate(taskId).unwrap()
    suspend fun exportNote(taskId: String): Response<ResponseBody> = remote.exportNote(taskId).requireHttpSuccess()
    suspend fun previewBilibili(request: BatchAppendPreviewDto) = remote.previewBilibili(request).unwrap()
    suspend fun previewAppendBilibili(batchId: String, request: BatchAppendPreviewDto) =
        remote.previewAppendBilibili(batchId, request).unwrap()
    suspend fun previewLocal(files: List<MultipartBody.Part>) = remote.previewLocal(files).unwrap()
    suspend fun previewAppendLocal(batchId: String, files: List<MultipartBody.Part>) =
        remote.previewAppendLocal(batchId, files).unwrap()
    suspend fun batchTasks(page: Int = 1, pageSize: Int = 20) = remote.batchTasks(page, pageSize).unwrap()
    suspend fun createBatch(request: BatchCreate) = remote.createBatch(request).unwrap()
    suspend fun batchDetail(batchId: String) = remote.batchDetail(batchId).unwrap()
    suspend fun updateBatch(batchId: String, request: BatchUpdate) = remote.updateBatch(batchId, request).unwrap()
    suspend fun appendBatchItems(batchId: String, request: BatchAppend) = remote.appendBatchItems(batchId, request).unwrap()
    suspend fun batchItems(batchId: String) = remote.batchItems(batchId).unwrap()
    suspend fun batchEvents(batchId: String, itemId: String) = remote.batchEvents(batchId, itemId).unwrap()
    suspend fun retryBatchItem(batchId: String, itemId: String) = remote.retryBatchItem(batchId, itemId).unwrap()
    suspend fun deleteBatchItem(batchId: String, itemId: String) = remote.deleteBatchItem(batchId, itemId).unwrap()
    suspend fun deleteBatchItems(batchId: String, itemIds: List<String>) = remote.deleteBatchItems(batchId, BatchDeleteRequest(itemIds)).unwrap()
    suspend fun exportBatch(batchId: String): Response<ResponseBody> = remote.exportBatch(batchId).requireHttpSuccess()
    suspend fun exportBatch(batchId: String, itemIds: List<String>): Response<ResponseBody> =
        remote.exportBatch(batchId, BatchExportRequest(itemIds)).requireHttpSuccess()
    suspend fun exportBatchItem(batchId: String, itemId: String): Response<ResponseBody> =
        remote.exportBatchItem(batchId, itemId).requireHttpSuccess()
    suspend fun providers() = remote.providers().unwrap()
    suspend fun models() = remote.models().unwrap()
    suspend fun sysHealth() = remote.sysHealth().unwrap()
}

private fun <T> Response<ApiResult<T>>.unwrap(): T {
    requireHttpSuccess()
    val body = body() ?: throw ApiHttpException(code(), "Empty HTTP response body")
    if (body.code != 0) throw ApiBusinessException(body.code, body.msg)
    return body.data ?: throw ApiBusinessException(body.code, "Successful response contains no data")
}

private fun <T> Response<T>.requireHttpSuccess(): Response<T> {
    if (!isSuccessful) throw ApiHttpException(code(), message().ifBlank { "HTTP ${code()}" })
    return this
}
