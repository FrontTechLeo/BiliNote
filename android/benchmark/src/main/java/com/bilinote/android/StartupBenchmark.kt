package com.bilinote.android

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupUnconfiguredService() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            device.executeShellCommand("pm clear $TARGET_PACKAGE")
            pressHome()
        },
    ) {
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.text("请先配置服务地址")), CONTENT_TIMEOUT_MS)) {
            "Timed out waiting for unconfigured-service state"
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.bilinote.android"
        const val CONTENT_TIMEOUT_MS = 5_000L
    }
}
