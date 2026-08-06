package com.vault999.android

import android.app.Application
import android.os.StrictMode
import androidx.work.Configuration
import com.vault999.android.playback.PlaybackSessionStore
import com.vault999.android.playback.PlaybackSessionStoreOwner
import com.vault999.android.playback.RoomPlaybackSessionStore

class VaultApplication : Application(), PlaybackSessionStoreOwner, Configuration.Provider {
    lateinit var graph: VaultGraph
        private set
    override val playbackSessionStore: PlaybackSessionStore by lazy {
        RoomPlaybackSessionStore(graph.database.queue(), graph.database.sync())
    }
    override val workManagerConfiguration: Configuration = Configuration.Builder()
        .setJobSchedulerJobIdRange(0x10000, 0x10fff)
        .build()

    override fun onCreate() {
        super.onCreate()
        graph = VaultGraph(this)
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build(),
            )
        }
    }
}
