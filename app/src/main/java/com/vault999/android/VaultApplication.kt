package com.vault999.android

import android.app.Application
import android.os.StrictMode

class VaultApplication : Application() {
    lateinit var graph: VaultGraph
        private set

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
