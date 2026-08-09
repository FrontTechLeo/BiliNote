package com.bilinote.android.baselineprofile

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiliNoteBaselineProfile {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndPrimaryNavigation() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        device.executeShellCommand("pm clear $TARGET_PACKAGE")
        pressHome()
        startActivityAndWait(benchmarkIntent("home"))
        check(device.wait(Until.hasObject(By.desc("benchmark-home-list")), CONTENT_TIMEOUT_MS)) {
            "Timed out waiting for benchmark-home-list"
        }
        device.findObject(By.desc("benchmark-nav-library")).click()
        check(device.wait(Until.hasObject(By.desc("benchmark-library-list")), CONTENT_TIMEOUT_MS)) {
            "Timed out waiting for benchmark-library-list"
        }
        device.findObject(By.desc("benchmark-nav-batch")).click()
        check(device.wait(Until.hasObject(By.desc("benchmark-batch-list")), CONTENT_TIMEOUT_MS)) {
            "Timed out waiting for benchmark-batch-list"
        }
        device.findObject(By.desc("benchmark-nav-reader")).click()
        check(device.wait(Until.hasObject(By.desc("benchmark-reader")), CONTENT_TIMEOUT_MS)) {
            "Timed out waiting for benchmark-reader"
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
