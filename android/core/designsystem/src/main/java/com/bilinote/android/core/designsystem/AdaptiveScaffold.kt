package com.bilinote.android.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Immutable
data class BiliNoteNavigationDestination(
    val id: String,
    val label: String,
    val selected: Boolean,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val contentDescription: String = label,
)

@Composable
fun AdaptiveScaffold(
    windowWidthSizeClass: WindowWidthSizeClass,
    destinations: List<BiliNoteNavigationDestination>,
    modifier: Modifier = Modifier,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    if (windowWidthSizeClass == WindowWidthSizeClass.Compact) {
        Scaffold(
            modifier = modifier,
            bottomBar = {
                NavigationBar {
                    destinations.forEach { destination ->
                        key(destination.id) {
                            NavigationBarItem(
                                selected = destination.selected,
                                onClick = destination.onClick,
                                enabled = destination.enabled,
                                modifier = Modifier.semantics {
                                    contentDescription = destination.contentDescription
                                },
                                icon = {
                                    Box(
                                        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                    ) { destination.icon() }
                                },
                                label = { androidx.compose.material3.Text(destination.label) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = floatingActionButton,
            content = { innerPadding ->
                Box(Modifier.fillMaxSize().padding(innerPadding)) { content() }
            },
        )
    } else {
        Row(modifier.fillMaxSize()) {
            NavigationRail {
                destinations.forEach { destination ->
                    key(destination.id) {
                        NavigationRailItem(
                            selected = destination.selected,
                            onClick = destination.onClick,
                            enabled = destination.enabled,
                            modifier = Modifier.semantics {
                                contentDescription = destination.contentDescription
                            },
                            icon = {
                                Box(
                                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                ) { destination.icon() }
                            },
                            label = { androidx.compose.material3.Text(destination.label) },
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                floatingActionButton = floatingActionButton,
            ) { innerPadding ->
                Box(Modifier.fillMaxSize().padding(innerPadding)) { content() }
            }
        }
    }
}

@Composable
fun AdaptiveListDetailPane(
    windowWidthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    listContent: (@Composable () -> Unit)? = null,
    detailContent: (@Composable () -> Unit)? = null,
) {
    val detail = detailContent ?: content
    when {
        windowWidthSizeClass == WindowWidthSizeClass.Compact || listContent == null -> {
            Box(modifier.fillMaxSize()) { content() }
        }

        windowWidthSizeClass == WindowWidthSizeClass.Medium -> {
            Row(modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().weight(0.38f)) { listContent() }
                Box(
                    Modifier.fillMaxHeight().weight(0.62f),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier.fillMaxHeight()
                            .widthIn(max = BiliNoteLayoutTokens.readerMaxWidth)
                            .fillMaxWidth(),
                    ) { detail() }
                }
            }
        }

        else -> {
            Row(modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().weight(0.30f)) { listContent() }
                Box(
                    Modifier.fillMaxHeight().weight(0.70f),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier.fillMaxHeight()
                            .widthIn(max = BiliNoteLayoutTokens.readerMaxWidth)
                            .fillMaxWidth(),
                    ) { detail() }
                }
            }
        }
    }
}
