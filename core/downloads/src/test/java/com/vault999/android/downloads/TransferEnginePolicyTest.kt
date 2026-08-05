package com.vault999.android.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class TransferEnginePolicyTest {
    @Test fun `state validator permits progress and rejects resurrection`() {
        val queued = DurableTransferState("one", TransferStage.QUEUED)
        val active = queued.copy(stage = TransferStage.DOWNLOADING, completedBytes = 20, totalBytes = 100)
        TransferStateTransitions.requireValid(queued, active)
        TransferStateTransitions.requireValid(active, active.copy(completedBytes = 40))
        val complete = active.copy(stage = TransferStage.COMPLETED, completedBytes = 100)
        TransferStateTransitions.requireValid(active, complete)
        assertTrue(runCatching { TransferStateTransitions.requireValid(complete, queued) }.isFailure)
    }

    @Test fun `range parser handles satisfied and unsatisfied forms`() {
        assertEquals(ContentRange(100, 199, 500), HttpRangeValidator.parseContentRange("bytes 100-199/500"))
        assertEquals(ContentRange(null, null, 500), HttpRangeValidator.parseContentRange("bytes */500"))
        assertEquals(null, HttpRangeValidator.parseContentRange("bytes 200-100/500"))
    }

    @Test fun `resume requires exact content range total and validator`() {
        val saved = ResumeMetadata("\"v1\"", 500, 100)
        assertTrue(HttpRangeValidator.evaluate(saved, response(206, "bytes 100-499/500", "\"v1\"")) is RangeDecision.Resume)
        assertTrue(HttpRangeValidator.evaluate(saved, response(206, "bytes 99-499/500", "\"v1\"")) is RangeDecision.Restart)
        assertTrue(HttpRangeValidator.evaluate(saved, response(206, "bytes 100-499/501", "\"v1\"")) is RangeDecision.Restart)
        assertTrue(HttpRangeValidator.evaluate(saved, response(206, "bytes 100-499/500", "\"v2\"")) is RangeDecision.Restart)
        assertTrue(HttpRangeValidator.evaluate(saved, response(200, null, "\"v1\"")) is RangeDecision.Restart)
        assertTrue(
            HttpRangeValidator.evaluate(
                ResumeMetadata("W/\"v1\"", 500, 100),
                response(206, "bytes 100-499/500", "W/\"v1\""),
            ) is RangeDecision.Restart,
        )
    }

    @Test fun `disk space policy includes reserve and collection coexistence`() {
        val policy = DiskSpacePolicy(reserveBytes = 100)
        assertEquals(DiskSpaceDecision.Sufficient(1_000, 900), policy.checkCollection(1_000, 300, 500))
        assertEquals(DiskSpaceDecision.Insufficient(899, 900), policy.checkCollection(899, 300, 500))
        assertEquals(DiskSpaceDecision.Unknown(900), policy.checkCollection(null, 300, 500))
    }

    @Test fun `coordinator persists cancel and stops active operation`() = runBlocking {
        val store = MemoryCheckpointStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = PersistentTransferCoordinator(store, scope)
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        try {
            coordinator.enqueue(DurableTransferState("cancel-me", TransferStage.QUEUED)) { state, save ->
                save(state.copy(stage = TransferStage.DOWNLOADING, totalBytes = 100))
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
            started.await()
            coordinator.cancel("cancel-me")
            stopped.await()
            assertEquals(TransferStage.CANCELLED, store.load("cancel-me")?.stage)
        } finally {
            coordinator.close()
        }
    }

    @Test fun `coordinator deterministically requeues interrupted durable state`() = runBlocking {
        val store = MemoryCheckpointStore(
            DurableTransferState("recover-me", TransferStage.DOWNLOADING, completedBytes = 40, totalBytes = 100),
        )
        val coordinator = PersistentTransferCoordinator(store, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val completed = CompletableDeferred<Unit>()
        try {
            coordinator.recover(
                mapOf("recover-me" to DurableTransferOperation { queued, save ->
                    assertEquals(TransferStage.QUEUED, queued.stage)
                    save(queued.copy(stage = TransferStage.DOWNLOADING))
                    save(queued.copy(stage = TransferStage.COMPLETED, completedBytes = 100))
                    completed.complete(Unit)
                }),
            )
            completed.await()
            assertEquals(TransferStage.COMPLETED, store.load("recover-me")?.stage)
        } finally {
            coordinator.close()
        }
    }

    private fun response(status: Int, contentRange: String?, etag: String?): Response = Response.Builder()
        .request(Request.Builder().url("https://example.test/file").build())
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("test")
        .apply {
            if (contentRange != null) header("Content-Range", contentRange)
            if (etag != null) header("ETag", etag)
        }
        .body(ByteArray(400).toResponseBody())
        .build()

    private class MemoryCheckpointStore(vararg initial: DurableTransferState) : TransferCheckpointStore {
        private val states = ConcurrentHashMap(initial.associateBy { it.id })
        override suspend fun load(id: String): DurableTransferState? = states[id]
        override suspend fun save(state: DurableTransferState) { states[state.id] = state }
    }
}
