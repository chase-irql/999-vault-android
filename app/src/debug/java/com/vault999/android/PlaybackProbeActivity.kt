package com.vault999.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.vault999.android.playback.PlaybackController

/** Debug-only lifecycle host used by connected tests; it owns no player itself. */
class PlaybackProbeActivity : ComponentActivity() {
    lateinit var playbackController: PlaybackController
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackController = PlaybackController(applicationContext).also(PlaybackController::connect)
    }

    override fun onDestroy() {
        playbackController.close()
        super.onDestroy()
    }
}
