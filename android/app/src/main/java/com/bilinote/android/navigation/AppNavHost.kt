package com.bilinote.android.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bilinote.android.AppDependencies
import com.bilinote.android.core.designsystem.AdaptiveScaffold
import com.bilinote.android.core.designsystem.BiliNoteNavigationDestination
import com.bilinote.android.feature.batch.BatchDetailScreen
import com.bilinote.android.feature.batch.BatchExport
import com.bilinote.android.feature.batch.BatchScreen
import com.bilinote.android.home.HomeScreen
import com.bilinote.android.home.HomeViewModel
import com.bilinote.android.library.LibraryScreen
import com.bilinote.android.library.LibraryTask
import com.bilinote.android.library.LibraryViewModel
import com.bilinote.android.reader.ReaderExport
import com.bilinote.android.reader.ReaderLoadState
import com.bilinote.android.reader.ReaderScreen
import com.bilinote.android.reader.ReaderViewModel
import com.bilinote.android.feature.settings.SavedSettings
import com.bilinote.android.feature.settings.SettingsScreen
import com.bilinote.android.feature.settings.SettingsViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Composable
fun AppNavHost(
    windowWidthSizeClass: WindowWidthSizeClass,
    navController: NavHostController = rememberNavController(),
    appDependencies: AppDependencies? = null,
) {
    val context = LocalContext.current
    val dependencies = appDependencies ?: remember(context.applicationContext) {
        AppDependencies(context.applicationContext)
    }
    val settings by dependencies.settingsRepository.observeSettings.collectAsState(initial = SavedSettings())
    val backendConfiguration = remember(dependencies, settings.backendUrl, settings.accessToken) {
        dependencies.configuredBackend(settings)
    }
    val backend = backendConfiguration.backend
    val pendingReaderExport = remember { mutableStateOf<ReaderExport?>(null) }
    val pendingBatchExport = remember { mutableStateOf<BatchExport?>(null) }
    val exportError = remember { mutableStateOf<String?>(null) }
    val ioScope = rememberCoroutineScope()
    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val readerExport = pendingReaderExport.value
        val batchExport = pendingBatchExport.value
        pendingReaderExport.value = null
        pendingBatchExport.value = null
        val body = readerExport?.body ?: batchExport?.bytes
        if (uri != null && body != null) {
            ioScope.launch(Dispatchers.IO) {
                val error = try {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("无法打开导出文件")
                    output.use { it.write(body) }
                    null
                } catch (exception: Exception) {
                    exception.message ?: "导出文件写入失败"
                }
                withContext(Dispatchers.Main.immediate) {
                    exportError.value = error
                }
            }
        }
    }

    fun requestExport(export: ReaderExport) {
        exportError.value = null
        pendingReaderExport.value = export
        createDocument.launch(export.fileName)
    }

    fun requestBatchExport(export: BatchExport) {
        exportError.value = null
        pendingBatchExport.value = export
        createDocument.launch(export.fileName)
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route.orEmpty()
    val selectedRoute = when {
        currentRoute.startsWith(AppDestination.READER_ROUTE) -> AppDestination.Home.route
        currentRoute.startsWith(AppDestination.BATCH_DETAIL_ROUTE) -> AppDestination.Batch.route
        else -> currentRoute
    }
    val navigationMode = windowWidthSizeClass.navigationModeLabel()
    val destinations = listOf(
        primaryDestination(AppDestination.Home.route, "Home", "⌂", selectedRoute, navigationMode) {
            navController.navigateTopLevel(AppDestination.Home.route)
        },
        primaryDestination(AppDestination.Batch.route, "Batch", "▦", selectedRoute, navigationMode) {
            navController.navigateTopLevel(AppDestination.Batch.route)
        },
        primaryDestination(AppDestination.Library.route, "Library", "▤", selectedRoute, navigationMode) {
            navController.navigateTopLevel(AppDestination.Library.route)
        },
        primaryDestination(AppDestination.Settings.route, "Settings", "⚙", selectedRoute, navigationMode) {
            navController.navigateTopLevel(AppDestination.Settings.route)
        },
    )

    val isDetailRoute = currentRoute.startsWith(AppDestination.READER_ROUTE) ||
        currentRoute.startsWith(AppDestination.BATCH_DETAIL_ROUTE)
    BackHandler(enabled = isDetailRoute) { navController.popBackStack() }

    Box(Modifier.fillMaxSize()) {
        AdaptiveScaffold(windowWidthSizeClass = windowWidthSizeClass, destinations = destinations) {
            NavHost(
                navController = navController,
                startDestination = AppDestination.Home.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(AppDestination.Home.route) {
                    if (backend == null) {
                        ServiceNotConfiguredPane(
                            message = backendConfiguration.errorMessage ?: "请先配置服务地址",
                        ) { navController.navigate(AppDestination.Settings.route) }
                    } else {
                        val factory = remember(backend) { backend.homeViewModelFactory() }
                        val viewModel: HomeViewModel = viewModel(
                            key = "home:${backend.identity}",
                            factory = factory,
                        )
                        HomeScreen(
                            viewModel = viewModel,
                            windowWidthSizeClass = windowWidthSizeClass,
                            onOpenReader = { taskId ->
                                navController.navigate(AppDestination.Reader(taskId).route)
                            },
                        )
                    }
                }
                composable(AppDestination.Batch.route) {
                    if (backend == null) {
                        ServiceNotConfiguredPane(
                            message = backendConfiguration.errorMessage ?: "请先配置服务地址",
                        ) { navController.navigate(AppDestination.Settings.route) }
                    } else {
                        val factory = remember(backend) { backend.batchViewModelFactory() }
                        val viewModel: com.bilinote.android.feature.batch.BatchViewModel = viewModel(
                            key = "batch:${backend.identity}",
                            factory = factory,
                        )
                        BatchScreen(
                            viewModel = viewModel,
                            windowWidthSizeClass = windowWidthSizeClass,
                            onOpenDetail = { batchId ->
                                navController.navigate(AppDestination.BatchDetail(batchId).route)
                            },
                            onOpenReader = { taskId ->
                                navController.navigate(AppDestination.Reader(taskId).route)
                            },
                            onExport = ::requestBatchExport,
                        )
                    }
                }
                composable(AppDestination.Library.route) {
                    if (backend == null) {
                        ServiceNotConfiguredPane(
                            message = backendConfiguration.errorMessage ?: "请先配置服务地址",
                        ) { navController.navigate(AppDestination.Settings.route) }
                    } else {
                        val factory = remember(backend) { backend.libraryViewModelFactory() }
                        val viewModel: LibraryViewModel = viewModel(
                            key = "library:${backend.identity}",
                            factory = factory,
                        )
                        val libraryState by viewModel.uiState.collectAsState()
                        val libraryActionError = remember { mutableStateOf<String?>(null) }
                        val onRegenerate: (String) -> Unit = { taskId ->
                            libraryActionError.value = null
                            ioScope.launch(Dispatchers.IO) {
                                try {
                                    backend.api.regenerate(taskId)
                                    withContext(Dispatchers.Main.immediate) { viewModel.refresh() }
                                } catch (exception: Exception) {
                                    withContext(Dispatchers.Main.immediate) {
                                        libraryActionError.value = exception.message ?: "重试失败"
                                    }
                                }
                            }
                        }
                        Box(Modifier.fillMaxSize()) {
                            LibraryScreen(
                                viewModel = viewModel,
                                windowWidthSizeClass = windowWidthSizeClass,
                                onOpenReader = { taskId ->
                                    navController.navigate(AppDestination.Reader(taskId).route)
                                },
                                onExport = { taskId ->
                                    navController.navigate(readerExportRoute(taskId))
                                },
                                onRegenerate = onRegenerate,
                            )
                            if (windowWidthSizeClass == WindowWidthSizeClass.Compact) {
                                libraryState.tasks
                                    .firstOrNull { it.taskId == libraryState.selectedTaskId }
                                    ?.let { task ->
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.BottomCenter,
                                        ) {
                                            CompactLibraryActions(
                                                task = task,
                                                onClear = { viewModel.selectTask(null) },
                                                onOpenReader = {
                                                    navController.navigate(AppDestination.Reader(task.taskId).route)
                                                },
                                                onExport = {
                                                    navController.navigate(readerExportRoute(task.taskId))
                                                },
                                                onRegenerate = onRegenerate,
                                            )
                                        }
                                    }
                            }
                            libraryActionError.value?.let { message ->
                                Text(
                                    text = "操作失败：$message",
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                        .testTag("library-action-error")
                                        .semantics { contentDescription = "操作失败：$message" },
                                )
                            }
                        }
                    }
                }
                composable(AppDestination.Settings.route) {
                    val factory = remember(dependencies) { dependencies.settingsViewModelFactory() }
                    val viewModel: SettingsViewModel = viewModel(key = "settings", factory = factory)
                    SettingsScreen(viewModel)
                }
                composable(
                    route = AppDestination.READER_ROUTE_PATTERN + "?${READER_EXPORT_ARGUMENT}={$READER_EXPORT_ARGUMENT}",
                    arguments = listOf(
                        navArgument("taskId") { type = NavType.StringType },
                        navArgument(READER_EXPORT_ARGUMENT) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { entry ->
                    val taskId = entry.arguments?.getString("taskId").orEmpty()
                    val shouldExport = entry.arguments?.getBoolean(READER_EXPORT_ARGUMENT) == true
                    if (backend == null || taskId.isBlank()) {
                        ServiceNotConfiguredPane(
                            message = backendConfiguration.errorMessage ?: "请先配置服务地址",
                        ) { navController.navigate(AppDestination.Settings.route) }
                    } else {
                        val relatedTasks by backend.observeRecentTasks().collectAsState(initial = emptyList())
                        val factory = remember(backend, taskId) { backend.readerViewModelFactory(taskId) }
                        val viewModel: ReaderViewModel = viewModel(
                            key = "reader:${backend.identity}:$taskId",
                            factory = factory,
                        )
                        LaunchedEffect(viewModel, shouldExport) {
                            if (shouldExport) {
                                exportWhenReady(viewModel)
                            }
                        }
                        ReaderScreen(
                            viewModel = viewModel,
                            windowWidthSizeClass = windowWidthSizeClass,
                            accessToken = backend.accessToken,
                            onBack = { navController.popBackStack() },
                            onOpenSource = { sourceUrl -> openExternalUri(context, sourceUrl) },
                            onOpenTimeLink = { seconds ->
                                val task = (viewModel.uiState.value.loadState as? ReaderLoadState.Success)?.task
                                task?.sourceUrl?.takeIf(String::isNotBlank)?.let { sourceUrl ->
                                    openExternalUri(context, sourceUrl.withTime(seconds))
                                }
                            },
                            onExport = ::requestExport,
                            relatedTasks = relatedTasks,
                            onSelectRelatedTask = { task ->
                                navController.navigate(AppDestination.Reader(task.taskId).route)
                            },
                        )
                    }
                }
                composable(
                    route = AppDestination.BATCH_DETAIL_ROUTE_PATTERN,
                    arguments = listOf(navArgument("batchId") { type = NavType.StringType }),
                ) { entry ->
                    val batchId = entry.arguments?.getString("batchId").orEmpty()
                    if (backend == null || batchId.isBlank()) {
                        ServiceNotConfiguredPane(
                            message = backendConfiguration.errorMessage ?: "请先配置服务地址",
                        ) { navController.navigate(AppDestination.Settings.route) }
                    } else {
                        val factory = remember(backend) { backend.batchViewModelFactory() }
                        val viewModel: com.bilinote.android.feature.batch.BatchViewModel = viewModel(
                            key = "batch:${backend.identity}",
                            factory = factory,
                        )
                        BatchDetailScreen(
                            viewModel = viewModel,
                            batchId = batchId,
                            onOpenReader = { taskId ->
                                navController.navigate(AppDestination.Reader(taskId).route)
                            },
                            onExport = ::requestBatchExport,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
        exportError.value?.let { message ->
            Text(
                text = "导出失败：$message",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("export-error")
                    .semantics { contentDescription = "导出失败：$message" },
            )
        }
    }
}

@Composable
private fun ServiceNotConfiguredPane(
    message: String,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("service-not-configured")
            .semantics { contentDescription = message }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(message)
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) { Text("前往设置") }
    }
}

private fun openExternalUri(context: Context, value: String) {
    if (value.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
    } catch (_: Exception) {
        // The caller only needs best-effort external navigation.
    }
}

private fun String.withTime(seconds: Int): String =
    try {
        Uri.parse(this).buildUpon().appendQueryParameter("t", seconds.toString()).build().toString()
    } catch (_: Exception) {
        this
    }

private const val READER_EXPORT_ARGUMENT = "export"

private fun readerExportRoute(taskId: String): String =
    "${AppDestination.Reader(taskId).route}?$READER_EXPORT_ARGUMENT=true"

@Composable
private fun CompactLibraryActions(
    task: LibraryTask,
    onClear: () -> Unit,
    onOpenReader: () -> Unit,
    onExport: () -> Unit,
    onRegenerate: (taskId: String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(task.title.ifBlank { task.taskId })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.canOpenReader) {
                    Button(
                        onClick = onOpenReader,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) { Text("阅读") }
                }
                if (task.canExport) {
                    OutlinedButton(
                        onClick = onExport,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) { Text("导出") }
                }
                if (task.canRegenerate) {
                    OutlinedButton(
                        onClick = { onRegenerate(task.taskId) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) { Text("重试") }
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Text("返回列表") }
            }
        }
    }
}

private suspend fun exportWhenReady(viewModel: ReaderViewModel) {
    val state = viewModel.uiState
        .map { it.loadState }
        .filter { it !is ReaderLoadState.Loading }
        .first()
    if (state is ReaderLoadState.Success) viewModel.export()
}

private fun primaryDestination(
    id: String,
    label: String,
    icon: String,
    selectedRoute: String,
    navigationMode: String,
    onClick: () -> Unit,
) = BiliNoteNavigationDestination(
    id = id,
    label = label,
    selected = selectedRoute == id,
    icon = { Text(icon) },
    onClick = onClick,
    contentDescription = "$navigationMode:$label",
)

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(AppDestination.Home.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun WindowWidthSizeClass.navigationModeLabel(): String =
    if (this == WindowWidthSizeClass.Compact) "navigation-bottom" else "navigation-rail"
