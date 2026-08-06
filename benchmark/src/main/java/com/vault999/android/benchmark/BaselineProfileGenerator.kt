package com.vault999.android.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        device.executeShellCommand(SEED_COMMAND)
        device.executeShellCommand("am force-stop $PACKAGE")
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        device.findObject(By.desc("Library tab"))?.click()
        device.wait(Until.hasObject(By.text("Your Library")), 3_000)
        device.wait(Until.hasObject(By.textStartsWith("Benchmark playlist")), 8_000)
        device.findObject(By.desc("Archive tab"))?.click()
        device.wait(Until.hasObject(By.text("The Vault")), 3_000)
        device.findObject(By.desc("Listen tab"))?.click()
        device.wait(Until.hasObject(By.desc("Start endless listen")), 3_000)
        device.findObject(By.desc("Start endless listen"))?.click()
        device.wait(Until.hasObject(By.descContains("Mini player")), 8_000)
        device.findObject(By.descContains("Mini player"))?.click()
        device.wait(Until.hasObject(By.text("Now Playing")), 3_000)
    }

    private companion object {
        const val PACKAGE = "com.vault999.android"
        const val SEED_COMMAND = "am start -W -a com.vault999.android.benchmark.SEED -n com.vault999.android/com.vault999.android.BenchmarkSeedActivity"
    }
}
