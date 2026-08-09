package com.bilinote.android.feature.batch

import com.bilinote.android.network.BiliNoteApi
import com.bilinote.android.network.dto.BatchAppend
import com.bilinote.android.network.dto.BatchAppendPreviewDto
import com.bilinote.android.network.dto.BatchCreate
import com.bilinote.android.network.dto.BatchUpdate
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MultipartBody

interface BatchRepository {
    /** Backend identity used to isolate feature-local caches. */
    val serviceBaseUrl: String

    suspend fun previewBilibili(sourceUrl: String): BatchPreview

    suspend fun previewLocal(files: List<MultipartBody.Part>): BatchPreview

    suspend fun previewAppendBilibili(batchId: String, sourceUrl: String): BatchPreview

    suspend fun previewAppendLocal(batchId: String, files: List<MultipartBody.Part>): BatchPreview

    suspend fun batchTasks(page: Int = 1, pageSize: Int = 20): BatchTaskPage

    suspend fun createBatch(request: BatchCreateRequest): BatchTask

    suspend fun batchDetail(batchId: String): BatchTask

    suspend fun updateBatch(batchId: String, request: BatchUpdateRequest): BatchTask

    suspend fun appendBatchItems(batchId: String, request: BatchAppendRequest): BatchAppendResult

    suspend fun batchItems(batchId: String): List<BatchItem>

    suspend fun batchEvents(batchId: String, itemId: String): List<BatchEvent>

    suspend fun retryBatchItem(batchId: String, itemId: String): BatchItem

    suspend fun deleteBatchItem(batchId: String, itemId: String): BatchDeleteResult

    suspend fun deleteBatchItems(batchId: String, itemIds: List<String>): BatchDeleteResult

    suspend fun exportBatch(batchId: String, itemIds: List<String>? = null): BatchExport

    suspend fun exportBatchItem(batchId: String, itemId: String): BatchExport
}

class RetrofitBatchRepository(
    private val api: BiliNoteApi,
    override val serviceBaseUrl: String,
) : BatchRepository {
    init {
        require(serviceBaseUrl.isNotBlank()) { "serviceBaseUrl must not be blank" }
    }
    override suspend fun previewBilibili(sourceUrl: String): BatchPreview =
        api.previewBilibili(BatchAppendPreviewDto(sourceUrl.trim())).toUiModel()

    override suspend fun previewLocal(files: List<MultipartBody.Part>): BatchPreview =
        api.previewLocal(files.sortedByFileName()).toUiModel().sortedByPosition()

    override suspend fun previewAppendBilibili(batchId: String, sourceUrl: String): BatchPreview =
        api.previewAppendBilibili(batchId, BatchAppendPreviewDto(sourceUrl.trim())).toUiModel()

    override suspend fun previewAppendLocal(batchId: String, files: List<MultipartBody.Part>): BatchPreview =
        api.previewAppendLocal(batchId, files.sortedByFileName()).toUiModel().sortedByPosition()

    override suspend fun batchTasks(page: Int, pageSize: Int): BatchTaskPage =
        api.batchTasks(page, pageSize).let { response ->
            BatchTaskPage(
                items = response.items.orEmpty().map { it.toUiModel() },
                total = response.total ?: 0,
                page = response.page ?: page,
                pageSize = response.pageSize ?: pageSize,
            )
        }

    override suspend fun createBatch(request: BatchCreateRequest): BatchTask =
        api.createBatch(
            BatchCreate(
                title = request.title,
                sourceType = request.sourceType,
                sourceValue = request.sourceValue,
                config = request.config.toDto(),
                items = request.items.map(BatchPreviewItem::toDto),
            ),
        ).toUiModel()

    override suspend fun batchDetail(batchId: String): BatchTask = api.batchDetail(batchId).toUiModel()

    override suspend fun updateBatch(batchId: String, request: BatchUpdateRequest): BatchTask =
        api.updateBatch(
            batchId,
            BatchUpdate(title = request.title, config = request.config?.toDto()),
        ).toUiModel()

    override suspend fun appendBatchItems(batchId: String, request: BatchAppendRequest): BatchAppendResult =
        api.appendBatchItems(batchId, BatchAppend(request.items.map(BatchPreviewItem::toDto))).let { response ->
            BatchAppendResult(
                task = response.task?.toUiModel(),
                addedItems = response.addedItems.orEmpty().map { it.toUiModel() },
                addedCount = response.addedCount ?: 0,
                skippedCount = response.skippedCount ?: 0,
            )
        }

    override suspend fun batchItems(batchId: String): List<BatchItem> =
        api.batchItems(batchId).map { it.toUiModel() }

    override suspend fun batchEvents(batchId: String, itemId: String): List<BatchEvent> =
        api.batchEvents(batchId, itemId).map { it.toUiModel() }

    override suspend fun retryBatchItem(batchId: String, itemId: String): BatchItem =
        api.retryBatchItem(batchId, itemId).toUiModel()

    override suspend fun deleteBatchItem(batchId: String, itemId: String): BatchDeleteResult =
        api.deleteBatchItem(batchId, itemId).let { response ->
            BatchDeleteResult(
                task = response.task?.toUiModel(),
                deletedCount = response.deletedCount ?: 0,
            )
        }

    override suspend fun deleteBatchItems(batchId: String, itemIds: List<String>): BatchDeleteResult =
        api.deleteBatchItems(batchId, itemIds).let { response ->
            BatchDeleteResult(
                task = response.task?.toUiModel(),
                deletedCount = response.deletedCount ?: 0,
            )
        }

    override suspend fun exportBatch(batchId: String, itemIds: List<String>?): BatchExport =
        (if (itemIds == null) api.exportBatch(batchId) else api.exportBatch(batchId, itemIds))
            .toExport(defaultFileName = "batch-$batchId.zip")

    override suspend fun exportBatchItem(batchId: String, itemId: String): BatchExport =
        api.exportBatchItem(batchId, itemId).toExport(defaultFileName = "batch-$batchId-item-$itemId.zip")
}

private suspend fun retrofit2.Response<okhttp3.ResponseBody>.toExport(defaultFileName: String): BatchExport =
    withContext(Dispatchers.IO) {
        val body = body() ?: throw IllegalStateException("导出响应为空")
        BatchExport(
            bytes = body.bytes(),
            fileName = headers().fileNameOrDefault(defaultFileName),
            contentType = body.contentType()?.toString(),
        )
    }

private fun Headers.fileNameOrDefault(default: String): String {
    val header = get("Content-Disposition").orEmpty()
    val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.getOrNull(1)
    val plain = Regex("filename=\\\"?([^;\\\"]+)", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.getOrNull(1)
    return (encoded?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: plain)
        ?.trim()
        ?.takeUnless(String::isEmpty)
        ?: default
}

private fun List<MultipartBody.Part>.sortedByFileName(): List<MultipartBody.Part> =
    naturalSortBy(this) { part -> part.headers?.fileNameOrDefault("").orEmpty() }
