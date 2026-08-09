package com.bilinote.android

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeListBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollFiftyTaskHome() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            device.executeShellCommand("pm clear $TARGET_PACKAGE")
            pressHome()
        },
    ) {
        startActivityAndWait(benchmarkIntent("home"))
        check(device.wait(Until.hasObject(By.desc("benchmark-home-list")), CONTENT_TIMEOUT_MS)) {
            "Timed out waiting for benchmark-home-list"
        }
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight * 3 / 4,
            device.displayWidth / 2,
            device.displayHeight / 4,
            20,
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "com.bilinote.android"
        const val CONTENT_TIMEOUT_MS = 5_000L

        fun benchmarkIntent(scenario: String) = Intent().apply {
            component = ComponentName(TARGET_PACKAGE, "$TARGET_PACKAGE.MainActivity")
            putExtra("benchmark_scenario", scenario)
        }
    }
}
