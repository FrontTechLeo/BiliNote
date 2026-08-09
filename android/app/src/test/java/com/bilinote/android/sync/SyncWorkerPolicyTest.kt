package com.bilinote.android.sync

import com.bilinote.android.network.ApiHttpException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class SyncWorkerPolicyTest {
    @Test
    fun `successful snapshot sync completes work`() = runBlocking {
        assertTrue(runSyncWithPolicy { } is androidx.work.ListenableWorker.Result.Success)
    }

    @Test
    fun `network failures request retry`() = runBlocking {
        assertTrue(runSyncWithPolicy { throw IOException("offline") } is androidx.work.ListenableWorker.Result.Retry)
    }

    @Test
    fun `server failures request retry`() = runBlocking {
        assertTrue(runSyncWithPolicy { throw ApiHttpException(503, "unavailable") } is androidx.work.ListenableWorker.Result.Retry)
    }

    @Test
    fun `client failures fail without retry`() = runBlocking {
        assertTrue(runSyncWithPolicy { throw ApiHttpException(401, "unauthorized") } is androidx.work.ListenableWorker.Result.Failure)
    }

    @Test
    fun `cancellation is not converted into a work result`() {
        runBlocking {
            assertFailsWith<CancellationException> {
                runSyncWithPolicy { throw CancellationException("cancelled") }
            }
        }
    }
}
