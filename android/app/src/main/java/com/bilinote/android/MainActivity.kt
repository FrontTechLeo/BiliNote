package com.bilinote.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.bilinote.android.sync.WorkScheduler

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val benchmarkScenario = intent.getStringExtra(EXTRA_BENCHMARK_SCENARIO)
        if (benchmarkScenario == null) {
            WorkScheduler.schedule(this)
        }
        setContent {
            BiliNoteApp(
                windowSizeClass = calculateWindowSizeClass(this),
                benchmarkScenario = benchmarkScenario,
            )
        }
    }

    companion object {
        const val EXTRA_BENCHMARK_SCENARIO = "benchmark_scenario"
    }
}
