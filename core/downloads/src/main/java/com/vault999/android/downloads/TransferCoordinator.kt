package com.vault999.android.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class TransferStage {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    PAUSED,
    VALIDATING,
    EXTRACTING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class DurableTransferState(
    val id: String,
    val stage: TransferStage,
    val completedBytes: Long = 0,
    val totalBytes: Long? = null,
    val validator: String? = null,
    val checkpoint: String? = null,
    val failureCode: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(completedBytes >= 0)
        require(totalBytes == null || totalBytes >= completedBytes)
        if (stage != TransferStage.FAILED) require(failureCode == null) { "Only failed transfers have a failure code" }
    }
}

object TransferStateTransitions {
    private val allowed = mapOf(
        TransferStage.QUEUED to setOf(TransferStage.PREPARING, TransferStage.DOWNLOADING, TransferStage.CANCELLED, TransferStage.FAILED),
        TransferStage.PREPARING to setOf(TransferStage.QUEUED, TransferStage.DOWNLOADING, TransferStage.CANCELLED, TransferStage.FAILED),
        TransferStage.DOWNLOADING to setOf(TransferStage.QUEUED, TransferStage.PAUSED, TransferStage.VALIDATING, TransferStage.COMPLETED, TransferStage.CANCELLED, TransferStage.FAILED),
        TransferStage.PAUSED to setOf(TransferStage.QUEUED, TransferStage.DOWNLOADING, TransferStage.CANCELLED),
        TransferStage.VALIDATING to setOf(TransferStage.QUEUED, TransferStage.EXTRACTING, TransferStage.COMPLETED, TransferStage.CANCELLED, TransferStage.FAILED),
        TransferStage.EXTRACTING to setOf(TransferStage.QUEUED, TransferStage.PAUSED, TransferStage.COMPLETED, TransferStage.CANCELLED, TransferStage.FAILED),
        TransferStage.FAILED to setOf(TransferStage.QUEUED, TransferStage.CANCELLED),
        TransferStage.COMPLETED to emptySet(),
        TransferStage.CANCELLED to emptySet(),
    )

    fun requireValid(previous: DurableTransferState, next: DurableTransferState) {
        require(previous.id == next.id) { "Transfer identity cannot change" }
        if (previous.stage == next.stage) {
            require(next.stage in setOf(TransferStage.PREPARING, TransferStage.DOWNLOADING, TransferStage.VALIDATING, TransferStage.EXTRACTING)) {
                "Terminal or idle states cannot transition to themselves"
            }
        } else {
            require(next.stage in allowed.getValue(previous.stage)) {
                "Invalid transfer transition ${previous.stage} -> ${next.stage}"
            }
        }
        if (next.stage !in setOf(TransferStage.QUEUED, TransferStage.DOWNLOADING)) {
            require(next.completedBytes >= previous.completedBytes) { "Progress cannot move backwards" }
        }
    }
}

interface TransferCheckpointStore {
    suspend fun load(id: String): DurableTransferState?
    suspend fun save(state: DurableTransferState)
}

fun interface DurableTransferOperation {
    suspend fun run(checkpoint: DurableTransferState, save: suspend (DurableTransferState) -> Unit)
}

interface TransferCoordinator {
    suspend fun enqueue(initial: DurableTransferState, operation: DurableTransferOperation)
    suspend fun pause(id: String)
    suspend fun cancel(id: String)
    suspend fun recover(operations: Map<String, DurableTransferOperation>)
    fun close()
}

/**
 * Small durable authority used behind WorkManager/UIDT adapters. Every visible transition is
 * persisted before execution continues; cancellation cancels the actual coroutine doing I/O.
 */
class PersistentTransferCoordinator(
    private val store: TransferCheckpointStore,
    private val scope: CoroutineScope,
) : TransferCoordinator {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val stateLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun enqueue(initial: DurableTransferState, operation: DurableTransferOperation) {
        require(initial.stage == TransferStage.QUEUED)
        store.save(initial)
        launch(initial, operation)
    }

    override suspend fun pause(id: String) {
        lockFor(id).withLock {
            val current = store.load(id) ?: return
            if (current.stage !in setOf(TransferStage.DOWNLOADING, TransferStage.EXTRACTING)) return
            persistUnlocked(current, current.copy(stage = TransferStage.PAUSED))
        }
        jobs.remove(id)?.cancel(PauseCancellation())
    }

    override suspend fun cancel(id: String) {
        lockFor(id).withLock {
            val current = store.load(id) ?: return
            if (current.stage in setOf(TransferStage.COMPLETED, TransferStage.CANCELLED)) return
            persistUnlocked(current, current.copy(stage = TransferStage.CANCELLED))
        }
        jobs.remove(id)?.cancel(CancellationException("Transfer cancelled"))
    }

    override suspend fun recover(operations: Map<String, DurableTransferOperation>) {
        operations.forEach { (id, operation) ->
            val saved = store.load(id) ?: return@forEach
            if (saved.stage in RECOVERABLE) {
                val queued = saved.copy(stage = TransferStage.QUEUED)
                persist(saved, queued)
                launch(queued, operation)
            }
        }
    }

    override fun close() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun launch(initial: DurableTransferState, operation: DurableTransferOperation) {
        check(jobs[initial.id]?.isActive != true) { "Transfer is already running" }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                operation.run(initial) { next ->
                    lockFor(initial.id).withLock {
                        val current = store.load(initial.id) ?: error("Transfer checkpoint disappeared")
                        if (current.stage in setOf(TransferStage.PAUSED, TransferStage.CANCELLED)) {
                            throw CancellationException("Transfer was stopped")
                        }
                        persistUnlocked(current, next)
                    }
                }
            } catch (_: PauseCancellation) {
                // pause() persisted the durable state before cancelling I/O.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val current = store.load(initial.id) ?: throw failure
                if (current.stage !in setOf(TransferStage.CANCELLED, TransferStage.PAUSED, TransferStage.COMPLETED)) {
                    persist(current, current.copy(stage = TransferStage.FAILED, failureCode = failure.javaClass.simpleName))
                }
            } finally {
                jobs.remove(initial.id, currentCoroutineContext()[Job])
            }
        }
        jobs[initial.id] = job
        job.start()
    }

    private suspend fun persist(previous: DurableTransferState, next: DurableTransferState) {
        lockFor(previous.id).withLock { persistUnlocked(previous, next) }
    }

    private suspend fun persistUnlocked(previous: DurableTransferState, next: DurableTransferState) {
        TransferStateTransitions.requireValid(previous, next)
        store.save(next)
    }

    private fun lockFor(id: String): Mutex = stateLocks.computeIfAbsent(id) { Mutex() }

    private class PauseCancellation : CancellationException("Transfer paused")

    companion object {
        private val RECOVERABLE = setOf(
            TransferStage.QUEUED,
            TransferStage.PREPARING,
            TransferStage.DOWNLOADING,
            TransferStage.VALIDATING,
            TransferStage.EXTRACTING,
        )
    }
}
