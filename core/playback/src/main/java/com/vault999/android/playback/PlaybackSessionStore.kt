package com.vault999.android.playback

import com.vault999.android.model.QueueSnapshot

/**
 * Database-agnostic persistence seam implemented by the application layer. Implementations may
 * use Room, DataStore, or another durable store; calls are made from a background dispatcher.
 */
interface PlaybackSessionStore {
    suspend fun restore(): QueueSnapshot?
    suspend fun persist(snapshot: QueueSnapshot)
    suspend fun observePlayback(snapshot: QueueSnapshot, playing: Boolean, buffering: Boolean, monotonicMs: Long) {
        persist(snapshot)
    }
}

/** Implement this on the Application to inject durable playback state into the service. */
interface PlaybackSessionStoreOwner {
    val playbackSessionStore: PlaybackSessionStore
}

internal object NoOpPlaybackSessionStore : PlaybackSessionStore {
    override suspend fun restore(): QueueSnapshot? = null
    override suspend fun persist(snapshot: QueueSnapshot) = Unit
}
