package com.bilinote.android.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class SyncWorkerResourceLifecycleTest {
    @Test
    fun `resource is closed after block succeeds`() = runTest {
        val events = mutableListOf<String>()
        val result = withResource(
            create = {
                events += "factory"
                RecordingResource(events)
            },
            close = { it.close() },
        ) { resource ->
            events += "block"
            resource.value
        }

        assertEquals("value", result)
        assertEquals(listOf("factory", "block", "close"), events)
        assertEquals(1, events.count { it == "close" })
    }

    @Test
    fun `resource is closed after block throws ordinary exception`() = runTest {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("boom")

        assertFailsWith<IllegalStateException> {
            withResource(
                create = {
                    events += "factory"
                    RecordingResource(events)
                },
                close = { it.close() },
            ) {
                events += "block"
                throw failure
            }
        }

        assertEquals(listOf("factory", "block", "close"), events)
        assertEquals(1, events.count { it == "close" })
    }

    @Test
    fun `close failure is thrown when block succeeds`() = runTest {
        val closeFailure = IllegalStateException("close failed")

        val failure = assertFailsWith<IllegalStateException> {
            withResource(
                create = { RecordingResource(mutableListOf()) },
                close = { throw closeFailure },
            ) {
                "value"
            }
        }

        assertEquals(closeFailure, failure)
    }

    @Test
    fun `close failure is suppressed when block throws`() = runTest {
        val blockFailure = IllegalStateException("block failed")
        val closeFailure = IllegalArgumentException("close failed")

        val failure = assertFailsWith<IllegalStateException> {
            withResource(
                create = { RecordingResource(mutableListOf()) },
                close = { throw closeFailure },
            ) {
                throw blockFailure
            }
        }

        assertEquals(blockFailure, failure)
        assertEquals(listOf(closeFailure), failure.suppressed.toList())
    }

    @Test
    fun `resource is closed during real coroutine cancellation and close failure is suppressed`() = runTest {
        val events = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val closeFailure = IllegalArgumentException("close failed")
        var cancellation: CancellationException? = null

        val job = launch {
            try {
                withResource(
                    create = {
                        events += "factory"
                        RecordingResource(events)
                    },
                    close = {
                        it.close()
                        throw closeFailure
                    },
                ) {
                    events += "block"
                    started.complete(Unit)
                    awaitCancellation()
                }
            } catch (error: CancellationException) {
                cancellation = error
                throw error
            }
        }

        started.await()
        job.cancelAndJoin()

        assertEquals(listOf("factory", "block", "close"), events)
        assertEquals(1, events.count { it == "close" })
        val failure = requireNotNull(cancellation)
        assertEquals(listOf(closeFailure), failure.suppressed.toList())
    }

    private class RecordingResource(
        private val events: MutableList<String>,
    ) {
        val value = "value"

        fun close() {
            events += "close"
        }
    }
}

private fun runTest(block: suspend CoroutineScope.() -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
