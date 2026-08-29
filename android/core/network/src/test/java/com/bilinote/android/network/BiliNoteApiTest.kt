package com.bilinote.android.network

import com.bilinote.android.network.dto.GenerateNoteRequest
import com.bilinote.android.network.dto.BatchConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class BiliNoteApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: BiliNoteApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = createApi()
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `decodes successful response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"code\":0,\"msg\":\"ok\",\"data\":{\"task_id\":\"t1\",\"status\":\"PENDING\"}}"))
        assertEquals("PENDING", api.taskStatus("t1").status)
        assertEquals("/api/task_status/t1", server.takeRequest().path)
    }

    @Test fun `adds api prefix when configured with backend root address`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"code\":0,\"msg\":\"ok\",\"data\":{\"task_id\":\"t1\",\"status\":\"PENDING\"}}"))
        createApi(baseUrl = server.url("/")).taskStatus("t1")
        assertEquals("/api/task_status/t1", server.takeRequest().path)
    }

    @Test fun `maps business error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"code\":400,\"msg\":\"bad\",\"data\":null}"))
        var error: ApiBusinessException? = null
        try {
            api.taskStatus("t1")
        } catch (exception: ApiBusinessException) {
            error = exception
        }
        assertEquals(400, error?.code)
    }

    @Test fun `maps http 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server down"))
        val result = runCatching {
            api.taskStatus("t1")
        }
        assertTrue(result.exceptionOrNull() is ApiHttpException)
    }

    @Test fun `adds authorization only when token exists`() = runTest {
        server.enqueue(MockResponse().setBody("{\"code\":0,\"msg\":\"ok\",\"data\":{\"task_id\":\"t1\",\"status\":\"PENDING\"}}"))
        api.taskStatus("t1")
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `adds bearer authorization when token exists`() = runTest {
        server.enqueue(MockResponse().setBody("{\"code\":0,\"msg\":\"ok\",\"data\":{\"task_id\":\"t1\",\"status\":\"PENDING\"}}"))
        createApi(token = "secret-token").taskStatus("t1")
        assertEquals("Bearer secret-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `does not add authorization for whitespace token`() = runTest {
        server.enqueue(MockResponse().setBody("{\"code\":0,\"msg\":\"ok\",\"data\":{\"task_id\":\"t1\",\"status\":\"PENDING\"}}"))
        createApi(token = "  \t").taskStatus("t1")
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `replaces the configured base url and removes trailing slash`() = runTest {
        server.enqueue(MockResponse().setBody("{\"code\":0,\"msg\":\"ok\",\"data\":{\"task_id\":\"t1\",\"status\":\"PENDING\"}}"))
        createApi(baseUrl = server.url("/api///")).taskStatus("t1")
        assertEquals("/api/task_status/t1", server.takeRequest().path)
    }

    @Test fun `decodes numeric batch event ids`() = runTest {
        server.enqueue(MockResponse().setBody("{\"code\":0,\"msg\":\"ok\",\"data\":[{\"id\":7,\"status\":\"SUCCESS\"}]}"))
        assertEquals(7, createApi().batchEvents("b1", "i1").single().id)
    }

    @Test fun `serializes image compression with backend field name`() {
        val noteBody = NetworkModule.json.encodeToString(
            GenerateNoteRequest(
                videoUrl = "https://example.com/video",
                platform = "bilibili",
                quality = "medium",
                modelName = "model-1",
                providerId = "provider-1",
                imageCompression = "medium",
            ),
        )
        val batchBody = NetworkModule.json.encodeToString(BatchConfig(imageCompression = "original"))

        assertTrue(noteBody.contains("\"image_compression\":\"medium\""))
        assertTrue(batchBody.contains("\"image_compression\":\"original\""))
    }

    private fun createApi(
        token: String? = null,
        baseUrl: okhttp3.HttpUrl = server.url("/"),
    ): BiliNoteApi {
        val retrofit = NetworkModule.createRetrofit(baseUrl.toString(), token)
        return BiliNoteApi(retrofit.create(RetrofitBiliNoteApi::class.java))
    }
}
