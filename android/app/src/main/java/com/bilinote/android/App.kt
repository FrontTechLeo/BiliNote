package com.bilinote.android

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bilinote.android.core.designsystem.BiliNoteTheme
import com.bilinote.android.benchmark.BenchmarkContentScreen
import com.bilinote.android.navigation.AppNavHost

const val BILINOTE_APPLICATION_ID = "com.bilinote.android"

@Composable
fun BiliNoteApp(
    windowSizeClass: WindowSizeClass,
    benchmarkScenario: String? = null,
) {
    BiliNoteTheme {
        if (benchmarkScenario != null) {
            BenchmarkContentScreen(benchmarkScenario)
        } else {
            val context = LocalContext.current.applicationContext
            val appDependencies = remember(context) { AppDependencies(context) }
            AppNavHost(
                windowWidthSizeClass = windowSizeClass.widthSizeClass,
                appDependencies = appDependencies,
            )
        }
    }
}
