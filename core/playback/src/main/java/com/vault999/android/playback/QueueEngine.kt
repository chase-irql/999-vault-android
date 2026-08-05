package com.vault999.android.playback

import com.vault999.android.model.PlaybackMode
import com.vault999.android.model.QueueItem
import com.vault999.android.model.QueueSnapshot
import com.vault999.android.model.RepeatMode
import kotlin.random.Random

enum class AdvanceCause { NATURAL_COMPLETION, EXPLICIT_NEXT, EXPLICIT_PREVIOUS }

data class QueueTransition(val snapshot: QueueSnapshot, val selected: QueueItem?)

class QueueEngine(private val random: Random = Random.Default) {
    fun enqueue(snapshot: QueueSnapshot, item: QueueItem, playNext: Boolean = false): QueueSnapshot {
        if (snapshot.items.any { it.mediaId == item.mediaId }) return snapshot
        val insertion = if (playNext && snapshot.currentIndex >= 0) snapshot.currentIndex + 1 else snapshot.items.size
        val updated = snapshot.items.toMutableList().apply { add(insertion.coerceIn(0, size), item) }
        return snapshot.copy(items = updated, currentIndex = if (snapshot.currentIndex < 0) 0 else snapshot.currentIndex)
    }

    fun advance(snapshot: QueueSnapshot, cause: AdvanceCause): QueueTransition {
        val current = snapshot.items.getOrNull(snapshot.currentIndex)
        if (current == null) return QueueTransition(snapshot.copy(currentIndex = -1, positionMs = 0), null)
        if (cause == AdvanceCause.NATURAL_COMPLETION && snapshot.repeatMode == RepeatMode.ONE) {
            return QueueTransition(snapshot.copy(positionMs = 0), current)
        }
        if (cause == AdvanceCause.EXPLICIT_PREVIOUS) return previous(snapshot)

        val nextIndex = when {
            snapshot.shuffle && snapshot.items.size > 1 -> shuffledNextIndex(snapshot)
            snapshot.currentIndex + 1 < snapshot.items.size -> snapshot.currentIndex + 1
            snapshot.repeatMode == RepeatMode.ALL -> 0
            else -> -1
        }
        if (nextIndex < 0) return QueueTransition(snapshot.copy(positionMs = 0), null)
        val history = (snapshot.historyMediaIds + current.mediaId).takeLast(256)
        val next = snapshot.items[nextIndex]
        return QueueTransition(snapshot.copy(currentIndex = nextIndex, positionMs = 0, historyMediaIds = history), next)
    }

    private fun previous(snapshot: QueueSnapshot): QueueTransition {
        val previousId = snapshot.historyMediaIds.lastOrNull()
        val historyIndex = previousId?.let { id -> snapshot.items.indexOfFirst { it.mediaId == id } } ?: -1
        val previousIndex = when {
            historyIndex >= 0 -> historyIndex
            snapshot.currentIndex > 0 -> snapshot.currentIndex - 1
            snapshot.repeatMode == RepeatMode.ALL -> snapshot.items.lastIndex
            else -> -1
        }
        if (previousIndex < 0) return QueueTransition(snapshot.copy(positionMs = 0), snapshot.items[snapshot.currentIndex])
        return QueueTransition(
            snapshot.copy(currentIndex = previousIndex, positionMs = 0, historyMediaIds = snapshot.historyMediaIds.dropLast(1)),
            snapshot.items[previousIndex],
        )
    }

    private fun shuffledNextIndex(snapshot: QueueSnapshot): Int {
        val candidates = snapshot.items.indices.filter { it != snapshot.currentIndex && snapshot.items[it].available }
        return candidates.random(random)
    }
}

fun catalogNextIndex(currentIndex: Int, catalogSize: Int, shuffle: Boolean, random: Random = Random.Default): Int {
    if (catalogSize <= 0) return -1
    if (catalogSize == 1) return 0
    if (!shuffle) return (currentIndex + 1).mod(catalogSize)
    var candidate: Int
    do candidate = random.nextInt(catalogSize) while (candidate == currentIndex)
    return candidate
}

fun QueueSnapshot.enterMode(mode: PlaybackMode): QueueSnapshot = copy(playbackMode = mode)

