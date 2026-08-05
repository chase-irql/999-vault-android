package com.vault999.android.testing

import com.vault999.android.model.QueueItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun interface Clock { fun nowEpochMs(): Long }
fun interface MonotonicClock { fun nowNanos(): Long }

class MutableClock(startEpochMs: Long = 0) : Clock, MonotonicClock {
    private var current = startEpochMs
    override fun nowEpochMs(): Long = current
    override fun nowNanos(): Long = current * 1_000_000
    fun advanceBy(milliseconds: Long) { require(milliseconds >= 0); current += milliseconds }
}

class FakeStorage {
    private val files = ConcurrentHashMap<String, ByteArray>()
    fun input(path: String) = ByteArrayInputStream(files[path] ?: error("Missing fake file: $path"))
    fun output(path: String): ByteArrayOutputStream = object : ByteArrayOutputStream() {
        override fun close() { files[path] = toByteArray(); super.close() }
    }
    fun bytes(path: String): ByteArray? = files[path]?.copyOf()
    fun remove(path: String) { files.remove(path) }
}

data class FakePlayerState(val queue: List<QueueItem> = emptyList(), val index: Int = -1, val playing: Boolean = false, val positionMs: Long = 0)

class FakePlayer {
    private val mutable = MutableStateFlow(FakePlayerState())
    val state: StateFlow<FakePlayerState> = mutable
    fun load(queue: List<QueueItem>, index: Int = 0) {
        mutable.value = FakePlayerState(queue, if (queue.isEmpty()) -1 else index.coerceIn(queue.indices), false, 0)
    }
    fun play() { mutable.value = mutable.value.copy(playing = true) }
    fun pause() { mutable.value = mutable.value.copy(playing = false) }
    fun seek(positionMs: Long) { mutable.value = mutable.value.copy(positionMs = positionMs.coerceAtLeast(0)) }
}
