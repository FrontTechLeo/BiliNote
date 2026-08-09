package com.bilinote.android.navigation

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed interface AppDestination {
    val route: String

    data object Home : AppDestination {
        override val route: String = HOME_ROUTE
    }

    data object Batch : AppDestination {
        override val route: String = BATCH_ROUTE
    }

    data object Library : AppDestination {
        override val route: String = LIBRARY_ROUTE
    }

    data object Settings : AppDestination {
        override val route: String = SETTINGS_ROUTE
    }

    data class Reader(val taskId: String) : AppDestination {
        init {
            require(taskId.isNotEmpty()) { "taskId must not be empty" }
        }

        override val route: String = "$READER_ROUTE/${encodePathSegment(taskId)}"
    }

    data class BatchDetail(val batchId: String) : AppDestination {
        init {
            require(batchId.isNotEmpty()) { "batchId must not be empty" }
        }

        override val route: String = "$BATCH_DETAIL_ROUTE/${encodePathSegment(batchId)}"
    }

    companion object {
        const val HOME_ROUTE = "home"
        const val BATCH_ROUTE = "batch"
        const val LIBRARY_ROUTE = "library"
        const val SETTINGS_ROUTE = "settings"
        const val READER_ROUTE = "reader"
        const val BATCH_DETAIL_ROUTE = "batch-detail"

        const val READER_ROUTE_PATTERN = "$READER_ROUTE/{taskId}"
        const val BATCH_DETAIL_ROUTE_PATTERN = "$BATCH_DETAIL_ROUTE/{batchId}"

        fun fromRoute(route: String): AppDestination? {
            return when {
                route == HOME_ROUTE -> Home
                route == BATCH_ROUTE -> Batch
                route == LIBRARY_ROUTE -> Library
                route == SETTINGS_ROUTE -> Settings
                route.startsWith("$READER_ROUTE/") ->
                    route.singleEncodedPathSegmentOrNull(READER_ROUTE)?.let(::Reader)

                route.startsWith("$BATCH_DETAIL_ROUTE/") ->
                    route.singleEncodedPathSegmentOrNull(BATCH_DETAIL_ROUTE)?.let(::BatchDetail)

                else -> null
            }
        }
    }
}

private fun String.singleEncodedPathSegmentOrNull(prefix: String): String? {
    val encodedSegment = removePrefix("$prefix/")
    if (encodedSegment.isEmpty() || encodedSegment.any { it == '/' || it == '?' || it == '#' }) {
        return null
    }
    return encodedSegment.decodePathSegmentOrNull()?.takeIf(String::isNotEmpty)
}

private fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")

private fun String.decodePathSegmentOrNull(): String? = runCatching {
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())
}.getOrNull()
