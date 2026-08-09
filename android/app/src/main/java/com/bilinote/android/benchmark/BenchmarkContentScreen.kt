package com.bilinote.android.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bilinote.android.reader.MarkdownRenderer

private const val HOME = "home"
private const val LIBRARY = "library"
private const val BATCH = "batch"
private const val READER = "reader"

@Composable
fun BenchmarkContentScreen(initialScenario: String) {
    var scenario by remember { mutableStateOf(initialScenario.ifBlank { HOME }) }
    val markdown = remember { benchmarkMarkdown() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BenchmarkNavigationButton("首页", HOME, scenario) { scenario = HOME }
            BenchmarkNavigationButton("笔记库", LIBRARY, scenario) { scenario = LIBRARY }
            BenchmarkNavigationButton("合集", BATCH, scenario) { scenario = BATCH }
            BenchmarkNavigationButton("阅读器", READER, scenario) { scenario = READER }
        }
        when (scenario) {
            READER -> BenchmarkReader(markdown)
            BATCH -> BenchmarkCollectionList()
            LIBRARY -> BenchmarkTaskList("benchmark-library-list", "历史笔记")
            else -> BenchmarkTaskList("benchmark-home-list", "进行中的任务")
        }
    }
}

@Composable
private fun BenchmarkNavigationButton(
    label: String,
    scenario: String,
    selectedScenario: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .semantics { contentDescription = "benchmark-nav-$scenario" }
            .testTag("benchmark-nav-$scenario"),
    ) {
        Text(
            text = label,
            color = if (scenario == selectedScenario) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun BenchmarkTaskList(tag: String, heading: String) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            Modifier
                .fillMaxSize()
                .testTag(tag)
                .semantics { contentDescription = tag },
        ) {
            items((1..50).toList(), key = { it }) { index ->
                ListItem(
                    headlineContent = { Text("BiliNote 任务 $index") },
                    supportingContent = { Text(if (index % 3 == 0) "处理中" else "已完成") },
                )
            }
        }
    }
}

@Composable
private fun BenchmarkCollectionList() {
    BenchmarkTaskList("benchmark-batch-list", "合集任务")
}

@Composable
private fun BenchmarkReader(markdown: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("benchmark-reader")
            .semantics { contentDescription = "benchmark-reader" },
    ) {
        MarkdownRenderer(
            markdown = markdown,
            backendUrl = "https://benchmark.invalid",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun benchmarkMarkdown(): String = buildString {
    repeat(80) { index ->
        append("# 课程章节 ${index + 1}\n\n")
        append("这是用于性能基准的长 Markdown 正文，包含列表、链接和多段文本。 ".repeat(4))
        append("\n\n- 要点 A\n- 要点 B\n- 要点 C\n\n")
        if (index % 8 == 0) {
            append("![fixture image](data:image/png;base64,$ONE_PIXEL_PNG)\n\n")
        }
    }
}

private const val ONE_PIXEL_PNG =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
