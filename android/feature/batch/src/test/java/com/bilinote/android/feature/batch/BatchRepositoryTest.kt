package com.bilinote.android.feature.batch

import com.bilinote.android.network.BiliNoteApi
import com.bilinote.android.network.NetworkModule
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BatchRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: BatchRepository

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val retrofit = NetworkModule.createRetrofit(server.url("/").toString(), null)
        repository = RetrofitBatchRepository(
            BiliNoteApi(retrofit.create(com.bilinote.android.network.RetrofitBiliNoteApi::class.java)),
            serviceBaseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `preview bilibili maps dto fields and sends source url`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"data":{"preview_id":"p1","title":"Playlist","discovered_count":2,"skipped_count":1,"duplicate_count":1,"items":[{"id":"i1","title":"Video 1","source_url":"https://b.test/1","platform":"bilibili","position":2}]}}
                """.trimIndent(),
            ),
        )

        val preview = repository.previewBilibili("https://b.test/list")

        assertEquals("p1", preview.previewId)
        assertEquals("Playlist", preview.title)
        assertEquals(2, preview.discoveredCount)
        assertEquals(1, preview.skippedCount)
        assertEquals(1, preview.duplicateCount)
        assertEquals("i1", preview.items.single().id)
        assertEquals(2, preview.items.single().position)
        val request = server.takeRequest()
        assertEquals("/batch/preview/bilibili", request.path)
        assertEquals(
            "https://b.test/list",
            Regex("\"source_url\":\"([^\"]+)\"").find(request.body.readUtf8())?.groupValues?.get(1),
        )
    }

    @Test
    fun `export batch reads bytes on repository boundary and derives response metadata`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/zip")
                .setHeader("Content-Disposition", "attachment; filename*=UTF-8''batch-%E4%B8%80.zip")
                .setBody("zip-bytes"),
        )

        val export = repository.exportBatch("batch-1")

        assertArrayEquals("zip-bytes".toByteArray(), export.bytes)
        assertEquals("batch-一.zip", export.fileName)
        assertEquals("application/zip", export.contentType)
        assertEquals("/batch/tasks/batch-1/export", server.takeRequest().path)
    }

    @Test
    fun `batch detail maps task counters and config format`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"data":{"id":"b1","title":"Batch","status":"RUNNING","total_count":3,"completed_count":1,"failed_count":0,"running_count":2,"progress":0.33,"config":{"quality":"slow","style":"tutorial","format":["toc","screenshot"],"video_understanding":false,"video_interval":9,"grid_size":[3,3]}}}
                """.trimIndent(),
            ),
        )

        val task = repository.batchDetail("b1")

        assertEquals("b1", task.id)
        assertEquals(3, task.totalCount)
        assertEquals(1, task.completedCount)
        assertEquals(0, task.failedCount)
        assertEquals(2, task.runningCount)
        assertEquals(0.33, task.progress, 0.0)
        assertEquals("slow", task.config?.quality)
        assertEquals(listOf("toc"), task.config?.format)
        assertEquals(listOf(3, 3), task.config?.gridSize)
    }

    @Test
    fun `delete batch items maps result and sends item ids`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"data":{"deleted_count":2,"task":{"id":"b1","total_count":3}}}
                """.trimIndent(),
            ),
        )

        val result = repository.deleteBatchItems("b1", listOf("i2", "i1"))

        assertEquals(2, result.deletedCount)
        assertEquals("b1", result.task?.id)
        val request = server.takeRequest()
        assertEquals("/batch/tasks/b1/items/delete", request.path)
        assertEquals("{\"item_ids\":[\"i2\",\"i1\"]}", request.body.readUtf8())
    }

    @Test
    fun `local preview sorts files and returned positions naturally`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"data":{"items":[{"id":"10","title":"ten","position":10},{"id":"2","title":"two","position":2},{"id":"1","title":"one","position":1}]}}
                """.trimIndent(),
            ),
        )
        val files = listOf("clip10.mp4", "clip2.mp4", "clip1.mp4").map { fileName ->
            MultipartBody.Part.createFormData(
                "files",
                fileName,
                "video".toRequestBody("video/mp4".toMediaType()),
            )
        }

        val preview = repository.previewLocal(files)

        assertEquals(listOf("1", "2", "10"), preview.items.map { it.id })
        val body = server.takeRequest().body.readUtf8()
        val clip1Index = body.indexOf("filename=\"clip1.mp4\"")
        val clip2Index = body.indexOf("filename=\"clip2.mp4\"")
        val clip10Index = body.indexOf("filename=\"clip10.mp4\"")
        assertTrue(clip1Index >= 0)
        assertTrue(clip2Index >= 0)
        assertTrue(clip10Index >= 0)
        assertTrue(clip1Index < clip2Index)
        assertTrue(clip2Index < clip10Index)
    }
}
