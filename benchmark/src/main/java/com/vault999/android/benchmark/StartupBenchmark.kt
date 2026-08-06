package com.vault999.android.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun coldStartupAndArchiveFrameTiming() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.waitForIdle()
    }

    @Test
    fun catalogSteadyScrollFrameTiming() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            seedAndStopTarget()
            startActivityAndWait()
            if (device.hasObject(By.text("Now Playing"))) device.pressBack()
            device.findObject(By.desc("Archive tab"))?.click()
            check(device.wait(Until.hasObject(By.text("THE ARCHIVE, IN YOUR POCKET")), 3_000))
        },
    ) {
        val x = device.displayWidth / 2
        val top = device.displayHeight / 4
        val bottom = device.displayHeight * 3 / 4
        repeat(3) { device.swipe(x, bottom, x, top, 20) }
        repeat(3) { device.swipe(x, top, x, bottom, 20) }
        device.waitForIdle()
    }

    @Test
    fun myMusicSteadyScrollFrameTiming() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            seedAndStopTarget()
            startActivityAndWait()
            if (device.hasObject(By.text("Now Playing"))) device.pressBack()
            checkNotNull(device.findObject(By.desc("My Music tab"))).click()
            check(device.wait(Until.hasObject(By.text("My Music")), 3_000))
            check(device.wait(Until.hasObject(By.textStartsWith("Benchmark playlist")), 8_000))
        },
    ) {
        val x = device.displayWidth / 2
        val top = device.displayHeight / 4
        val bottom = device.displayHeight * 3 / 4
        repeat(3) { device.swipe(x, bottom, x, top, 20) }
        repeat(3) { device.swipe(x, top, x, bottom, 20) }
        device.waitForIdle()
    }

    @Test
    fun openNowPlayingFrameTiming() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            seedAndStopTarget()
            startActivityAndWait()
            if (device.hasObject(By.text("Now Playing"))) device.pressBack()
            device.findObject(By.desc("Archive tab"))?.click()
            check(device.wait(Until.hasObject(By.descContains("Mini player")), 8_000))
        },
    ) {
        checkNotNull(device.findObject(By.desc("Open Now Playing"))).click()
        check(device.wait(Until.hasObject(By.text("Now Playing")), 3_000))
        device.waitForIdle()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.seedAndStopTarget() {
        device.executeShellCommand(SEED_COMMAND)
        device.executeShellCommand("am force-stop $PACKAGE")
        pressHome()
    }

    private companion object {
        const val PACKAGE = "com.vault999.android"
        const val SEED_COMMAND = "am start -W -a com.vault999.android.benchmark.SEED -n com.vault999.android/com.vault999.android.BenchmarkSeedActivity"
    }
}
