package com.vault999.android.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private val runs = ConcurrentHashMap<String, ActiveRun>()
    private val stateLocks = ConcurrentHashMap<String, Mutex>()
    private val nextGeneration = AtomicLong()
    private val closed = AtomicBoolean()

    override suspend fun enqueue(initial: DurableTransferState, operation: DurableTransferOperation) {
        require(initial.stage == TransferStage.QUEUED)
        val run = lockFor(initial.id).withLock {
            check(!closed.get()) { "Transfer coordinator is closed" }
            check(runs[initial.id] == null) { "Transfer is already running" }
            // A new explicit enqueue is a new generation. It may intentionally reuse an ID after
            // cancellation, but callbacks from the prior generation can no longer mutate it.
            store.save(initial)
            createRun(initial, operation).also { runs[initial.id] = it }
        }
        if (closed.get()) {
            runs.remove(initial.id, run)
            run.job.cancel()
            error("Transfer coordinator is closed")
        }
        run.job.start()
    }

    override suspend fun pause(id: String) {
        var job: Job? = null
        lockFor(id).withLock {
            val current = store.load(id) ?: return
            if (current.stage !in setOf(TransferStage.DOWNLOADING, TransferStage.EXTRACTING)) return
            persistUnlocked(current, current.copy(stage = TransferStage.PAUSED))
            job = runs.remove(id)?.job
        }
        job?.cancel(PauseCancellation())
    }

    override suspend fun cancel(id: String) {
        var job: Job? = null
        lockFor(id).withLock {
            val current = store.load(id) ?: return
            if (current.stage in setOf(TransferStage.COMPLETED, TransferStage.CANCELLED)) return
            persistUnlocked(current, current.copy(stage = TransferStage.CANCELLED, failureCode = null))
            job = runs.remove(id)?.job
        }
        job?.cancel(CancellationException("Transfer cancelled"))
    }

    override suspend fun recover(operations: Map<String, DurableTransferOperation>) {
        operations.forEach { (id, operation) ->
            val run = lockFor(id).withLock {
                check(!closed.get()) { "Transfer coordinator is closed" }
                check(runs[id] == null) { "Transfer is already running" }
                val saved = store.load(id) ?: return@withLock null
                if (saved.stage !in RECOVERABLE) return@withLock null
                val queued = saved.copy(stage = TransferStage.QUEUED)
                persistUnlocked(saved, queued)
                createRun(queued, operation).also { runs[id] = it }
            }
            if (run != null && closed.get()) {
                runs.remove(id, run)
                run.job.cancel()
                error("Transfer coordinator is closed")
            }
            run?.job?.start()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // ConcurrentHashMap's collection-to-list helper can race a finishing job between
        // hasNext/next. Its weakly-consistent forEach is safe while runs remove themselves.
        val active = ArrayList<ActiveRun>()
        runs.forEach { _, run -> active += run }
        runs.clear()
        active.forEach { it.job.cancel() }
    }

    private fun createRun(initial: DurableTransferState, operation: DurableTransferOperation): ActiveRun {
        val generation = nextGeneration.incrementAndGet()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                operation.run(initial) { next ->
                    lockFor(initial.id).withLock {
                        requireCurrentRun(initial.id, generation)
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
                withContext(NonCancellable) {
                    lockFor(initial.id).withLock {
                        if (runs[initial.id]?.generation != generation) return@withLock
                        val current = store.load(initial.id) ?: throw failure
                        if (current.stage !in setOf(TransferStage.CANCELLED, TransferStage.PAUSED, TransferStage.COMPLETED)) {
                            persistUnlocked(
                                current,
                                current.copy(stage = TransferStage.FAILED, failureCode = failure.javaClass.simpleName),
                            )
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    lockFor(initial.id).withLock {
                        if (runs[initial.id]?.generation == generation) runs.remove(initial.id)
                    }
                }
            }
        }
        return ActiveRun(generation, job)
    }

    private fun requireCurrentRun(id: String, generation: Long) {
        if (runs[id]?.generation != generation) throw StaleRunCancellation()
    }

    private suspend fun persistUnlocked(previous: DurableTransferState, next: DurableTransferState) {
        TransferStateTransitions.requireValid(previous, next)
        store.save(next)
    }

    private fun lockFor(id: String): Mutex = stateLocks.computeIfAbsent(id) { Mutex() }

    private data class ActiveRun(val generation: Long, val job: Job)
    private class PauseCancellation : CancellationException("Transfer paused")
    private class StaleRunCancellation : CancellationException("A newer transfer generation owns this ID")

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
