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

        val nextIndex = if (snapshot.shuffle) {
            shuffledNextIndex(snapshot)
        } else {
            nextAvailableIndex(
                items = snapshot.items,
                currentIndex = snapshot.currentIndex,
                direction = 1,
                wrap = snapshot.repeatMode == RepeatMode.ALL,
            )
        }
        if (nextIndex < 0) return QueueTransition(snapshot.copy(positionMs = 0), null)
        val history = (snapshot.historyMediaIds + current.mediaId).takeLast(256)
        val next = snapshot.items[nextIndex]
        return QueueTransition(snapshot.copy(currentIndex = nextIndex, positionMs = 0, historyMediaIds = history), next)
    }

    private fun previous(snapshot: QueueSnapshot): QueueTransition {
        val previousId = snapshot.historyMediaIds.lastOrNull()
        val historyIndex = previousId?.let { id -> snapshot.items.indexOfFirst { it.mediaId == id && it.available } } ?: -1
        val previousIndex = when {
            historyIndex >= 0 -> historyIndex
            else -> nextAvailableIndex(
                items = snapshot.items,
                currentIndex = snapshot.currentIndex,
                direction = -1,
                wrap = snapshot.repeatMode == RepeatMode.ALL,
            )
        }
        if (previousIndex < 0) {
            return QueueTransition(
                snapshot.copy(positionMs = 0),
                snapshot.items[snapshot.currentIndex].takeIf(QueueItem::available),
            )
        }
        return QueueTransition(
            snapshot.copy(currentIndex = previousIndex, positionMs = 0, historyMediaIds = snapshot.historyMediaIds.dropLast(1)),
            snapshot.items[previousIndex],
        )
    }

    private fun shuffledNextIndex(snapshot: QueueSnapshot): Int {
        val candidates = snapshot.items.indices.filter { it != snapshot.currentIndex && snapshot.items[it].available }
        if (candidates.isNotEmpty()) return candidates.random(random)
        return snapshot.currentIndex.takeIf {
            snapshot.repeatMode == RepeatMode.ALL && snapshot.items[it].available
        } ?: -1
    }
}

internal fun nextAvailableIndex(
    items: List<QueueItem>,
    currentIndex: Int,
    direction: Int,
    wrap: Boolean,
): Int {
    if (items.isEmpty() || direction == 0) return -1
    val validCurrent = currentIndex.coerceIn(-1, items.lastIndex)
    var index = validCurrent + direction
    var inspected = 0
    while (inspected < items.size) {
        if (index !in items.indices) {
            if (!wrap) return -1
            index = if (direction > 0) 0 else items.lastIndex
        }
        if (index == validCurrent) return -1
        if (items[index].available) return index
        index += direction
        inspected++
    }
    return -1
}

data class PreparedQueue(val items: List<QueueItem>, val startIndex: Int)

/** Filters unavailable media while keeping the requested item selected when it is playable. */
fun preparePlayableQueue(items: List<QueueItem>, requestedStartIndex: Int): PreparedQueue {
    val requestedId = items.getOrNull(requestedStartIndex)?.mediaId
    val playable = items.filter(QueueItem::available)
    if (playable.isEmpty()) return PreparedQueue(emptyList(), -1)
    val fallbackId = items
        .drop(requestedStartIndex.coerceAtLeast(0))
        .firstOrNull(QueueItem::available)
        ?.mediaId
    val mappedIndex = playable.indexOfFirst { it.mediaId == (requestedId ?: fallbackId) }
        .takeIf { it >= 0 }
        ?: playable.indexOfFirst { it.mediaId == fallbackId }
    return PreparedQueue(playable, mappedIndex.takeIf { it >= 0 } ?: 0)
}

/** A buffering player with playWhenReady=true is already trying to play, so toggle means pause. */
fun shouldPauseOnToggle(playWhenReady: Boolean, buffering: Boolean): Boolean = playWhenReady || buffering

fun catalogNextIndex(currentIndex: Int, catalogSize: Int, shuffle: Boolean, random: Random = Random.Default): Int {
    if (catalogSize <= 0) return -1
    if (catalogSize == 1) return 0
    if (!shuffle) return (currentIndex + 1).mod(catalogSize)
    var candidate: Int
    do candidate = random.nextInt(catalogSize) while (candidate == currentIndex)
    return candidate
}

fun QueueSnapshot.enterMode(mode: PlaybackMode): QueueSnapshot = copy(playbackMode = mode)
