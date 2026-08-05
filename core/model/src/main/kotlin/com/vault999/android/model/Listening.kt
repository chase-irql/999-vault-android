package com.vault999.android.model

import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

data class PlaybackObservation(
    val monotonicMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val preloading: Boolean = false,
    val explicitSeek: Boolean = false,
)

class ListeningCreditTracker(
    private val songId: Long,
    private val durationSeconds: Long,
    private val source: String,
    private val eventId: () -> String = { UUID.randomUUID().toString() },
) {
    private var last: PlaybackObservation? = null
    private var accumulatedMs = 0L
    private var credited = false

    val thresholdMs: Long = min(30_000L, durationSeconds.coerceAtLeast(1) * 500L)

    fun observe(value: PlaybackObservation, wallClockEpochMs: Long): ListeningEvent? {
        val previous = last
        last = value
        if (credited || previous == null || !previous.playing || previous.preloading || value.preloading || value.explicitSeek) return null
        val elapsed = value.monotonicMs - previous.monotonicMs
        val positionDelta = value.positionMs - previous.positionMs
        if (elapsed !in 1..5_000 || positionDelta < 0 || abs(positionDelta - elapsed) > maxOf(2_000L, elapsed * 2)) return null
        accumulatedMs += min(elapsed, positionDelta.coerceAtLeast(0))
        if (accumulatedMs < thresholdMs) return null
        credited = true
        return ListeningEvent(
            id = eventId(),
            songId = songId,
            playedAtEpochMs = wallClockEpochMs,
            listenedSeconds = accumulatedMs / 1000,
            durationSeconds = durationSeconds,
            source = source,
        )
    }
}

data class WrappedSong(val songId: Long, val plays: Int, val listenedSeconds: Long)
data class WrappedSummary(
    val totalPlays: Int,
    val distinctSongs: Int,
    val listenedSeconds: Long,
    val topSongs: List<WrappedSong>,
    val coverageStartEpochMs: Long?,
)

object WrappedAggregator {
    fun aggregate(events: Iterable<ListeningEvent>, nowEpochMs: Long, days: Int?): WrappedSummary {
        val cutoff = days?.let { nowEpochMs - it * 86_400_000L }
        val eligible = events.asSequence()
            .filter { it.songId > 0 && it.playedAtEpochMs > 0 && (cutoff == null || it.playedAtEpochMs >= cutoff) }
            .distinctBy { it.id }
            .toList()
        val ranked = eligible.groupBy { it.songId }.map { (songId, songEvents) ->
            WrappedSong(songId, songEvents.size, songEvents.sumOf { it.listenedSeconds })
        }.sortedWith(compareByDescending<WrappedSong> { it.plays }.thenByDescending { it.listenedSeconds }.thenBy { it.songId })
        return WrappedSummary(
            totalPlays = eligible.size,
            distinctSongs = ranked.size,
            listenedSeconds = eligible.sumOf { it.listenedSeconds },
            topSongs = ranked,
            coverageStartEpochMs = eligible.minOfOrNull { it.playedAtEpochMs },
        )
    }

    fun union(local: Iterable<ListeningEvent>, remote: Iterable<ListeningEvent>): List<ListeningEvent> =
        (local + remote).associateBy { it.id }.values.sortedWith(compareByDescending<ListeningEvent> { it.playedAtEpochMs }.thenBy { it.id })
}

