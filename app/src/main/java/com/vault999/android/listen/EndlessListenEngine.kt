package com.vault999.android.listen

import com.vault999.android.model.CanonicalSong
import com.vault999.android.model.QueueItem
import com.vault999.android.model.SongCategory

enum class ListenMode {
    ALL,
    RELEASED,
    UNRELEASED,
    UNSURFACED,
    RECORDING_SESSION,
}

data class EndlessListenSession(
    val history: List<QueueItem> = emptyList(),
    val cursor: Int = -1,
    val lookAhead: List<QueueItem> = emptyList(),
    val generation: Long = 0,
    val refillError: String? = null,
) {
    val current: QueueItem? get() = history.getOrNull(cursor)
    val recents: List<QueueItem> get() = history.take(cursor.coerceAtLeast(0)).takeLast(8).reversed()
    val canGoBack: Boolean get() = cursor > 0
    val canGoForward: Boolean get() = cursor in 0 until history.lastIndex
}

data class EndlessListenState(
    val activeMode: ListenMode = ListenMode.ALL,
    val sessions: Map<ListenMode, EndlessListenSession> = emptyMap(),
) {
    val active: EndlessListenSession get() = sessions[activeMode] ?: EndlessListenSession()
}

data class ListenTransition(
    val state: EndlessListenState,
    val selected: QueueItem?,
)

/** Pure deterministic queue/history engine. No random or clock state is read. */
class EndlessListenEngine(
    private val seed: Int = 999,
    private val lookAheadSize: Int = 8,
    private val recentSize: Int = 8,
) {
    init {
        require(lookAheadSize > 0)
        require(recentSize > 0)
    }

    fun switchMode(state: EndlessListenState, mode: ListenMode): ListenTransition {
        val updated = state.copy(activeMode = mode)
        return ListenTransition(updated, updated.active.current)
    }

    fun start(state: EndlessListenState, mode: ListenMode, catalog: List<CanonicalSong>): ListenTransition {
        val playable = eligible(catalog, mode)
        if (playable.isEmpty()) {
            val failed = EndlessListenSession(refillError = "No playable songs are available for this mode.")
            val updated = state.copy(activeMode = mode, sessions = state.sessions + (mode to failed))
            return ListenTransition(updated, null)
        }
        val selected = choose(playable, excluded = emptySet(), generation = 0) ?: error("eligible catalog was empty")
        val initial = EndlessListenSession(history = listOf(selected), cursor = 0, generation = 1)
        val filled = refill(initial, playable)
        val updated = state.copy(activeMode = mode, sessions = state.sessions + (mode to filled))
        return ListenTransition(updated, selected)
    }

    fun next(state: EndlessListenState, catalog: List<CanonicalSong>): ListenTransition {
        val session = state.active
        if (session.canGoForward) {
            val moved = session.copy(cursor = session.cursor + 1)
            return replaceActive(state, moved, moved.current)
        }
        val playable = eligible(catalog, state.activeMode)
        val buffered = if (session.lookAhead.isEmpty()) refill(session, playable) else session
        val selected = buffered.lookAhead.firstOrNull()
            ?: return replaceActive(state, buffered.copy(refillError = refillMessage(playable)), buffered.current)
        val truncatedHistory = buffered.history.take(buffered.cursor + 1)
        val advanced = buffered.copy(
            history = (truncatedHistory + selected).takeLast(MAX_HISTORY),
            cursor = (truncatedHistory + selected).takeLast(MAX_HISTORY).lastIndex,
            lookAhead = buffered.lookAhead.drop(1),
            refillError = null,
        )
        return replaceActive(state, refill(advanced, playable), selected)
    }

    fun back(state: EndlessListenState): ListenTransition {
        val session = state.active
        if (!session.canGoBack) return ListenTransition(state, session.current)
        val moved = session.copy(cursor = session.cursor - 1)
        return replaceActive(state, moved, moved.current)
    }

    fun forward(state: EndlessListenState): ListenTransition {
        val session = state.active
        if (!session.canGoForward) return ListenTransition(state, session.current)
        val moved = session.copy(cursor = session.cursor + 1)
        return replaceActive(state, moved, moved.current)
    }

    fun refill(state: EndlessListenState, catalog: List<CanonicalSong>): EndlessListenState {
        val playable = eligible(catalog, state.activeMode)
        return replaceActive(state, refill(state.active, playable), selected = null).state
    }

    private fun refill(session: EndlessListenSession, playable: List<QueueItem>): EndlessListenSession {
        if (playable.isEmpty()) return session.copy(refillError = refillMessage(playable))
        var generation = session.generation
        val queue = session.lookAhead.toMutableList()
        val recentIds = session.history.take(session.cursor + 1).takeLast(recentSize).mapTo(linkedSetOf()) { it.mediaId }
        while (queue.size < lookAheadSize) {
            val hardExcluded = recentIds + queue.map { it.mediaId } + listOfNotNull(session.current?.mediaId)
            val softExcluded = queue.mapTo(linkedSetOf()) { it.mediaId } + listOfNotNull(session.current?.mediaId)
            val selected = choose(playable, hardExcluded, generation)
                ?: choose(playable, softExcluded, generation)
                ?: break
            queue += selected
            generation++
        }
        return session.copy(lookAhead = queue, generation = generation, refillError = null)
    }

    private fun eligible(catalog: List<CanonicalSong>, mode: ListenMode): List<QueueItem> = catalog.asSequence()
        .filter(CanonicalSong::isPlayable)
        .filter { song ->
            when (mode) {
                ListenMode.ALL -> true
                ListenMode.RELEASED -> song.category == SongCategory.RELEASED
                ListenMode.UNRELEASED -> song.category == SongCategory.UNRELEASED
                ListenMode.UNSURFACED -> song.category == SongCategory.UNSURFACED
                ListenMode.RECORDING_SESSION -> song.category == SongCategory.SESSION
            }
        }
        .distinctBy { it.id }
        .sortedWith(compareBy<CanonicalSong> { it.publicNumber }.thenBy { it.id })
        .map(CanonicalSong::asQueueItem)
        .toList()

    private fun choose(candidates: List<QueueItem>, excluded: Set<String>, generation: Long): QueueItem? {
        val available = candidates.filterNot { it.mediaId in excluded }
        if (available.isEmpty()) return null
        val index = Math.floorMod(seed.toLong() + generation * 31L, available.size.toLong()).toInt()
        return available[index]
    }

    private fun replaceActive(state: EndlessListenState, session: EndlessListenSession, selected: QueueItem?): ListenTransition =
        ListenTransition(state.copy(sessions = state.sessions + (state.activeMode to session)), selected)

    private fun refillMessage(playable: List<QueueItem>): String = if (playable.isEmpty()) {
        "The catalog is unavailable. Buffered listening can continue until the queue is empty."
    } else {
        "The listening queue could not be refilled. Retry when the catalog is available."
    }

    private companion object {
        const val MAX_HISTORY = 256
    }
}

private fun CanonicalSong.asQueueItem(): QueueItem = QueueItem(
    mediaId = "song:$id",
    title = title,
    artist = artist,
    uri = requireNotNull(streamUrl),
    artworkUri = artworkUrl,
    durationMs = durationSeconds?.times(1_000),
    canonicalSongId = id,
    local = false,
    available = true,
)
