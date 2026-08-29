package com.bilinote.android.reader

import java.net.URI
import java.util.Locale

private val BILI_NOTE_TIME_LINK = Regex(
    "\\*?Content-\\[?(\\d{1,2}):(\\d{2})\\]?\\*?",
    RegexOption.IGNORE_CASE,
)

internal fun normalizeBiliNoteTimeLinks(markdown: String): String =
    BILI_NOTE_TIME_LINK.replace(markdown) { match ->
        val minutes = match.groupValues[1].toIntOrNull() ?: return@replace match.value
        val seconds = match.groupValues[2].toIntOrNull() ?: return@replace match.value
        if (seconds !in 0..59) return@replace match.value
        val label = "Content-%02d:%02d".format(Locale.ROOT, minutes, seconds)
        "[$label](bilinote-time://${minutes * 60 + seconds})"
    }

internal fun shouldAttachImageAuthorization(imageUrl: String, backendUrl: String): Boolean {
    val image = runCatching { URI(imageUrl.trim()) }.getOrNull() ?: return false
    val backend = runCatching { URI(backendUrl.trim()) }.getOrNull() ?: return false
    val imageScheme = image.scheme?.lowercase(Locale.US) ?: return false
    val backendScheme = backend.scheme?.lowercase(Locale.US) ?: return false
    val imageHost = image.host?.lowercase(Locale.US) ?: return false
    val backendHost = backend.host?.lowercase(Locale.US) ?: return false
    val imagePort = image.effectivePort() ?: return false
    val backendPort = backend.effectivePort() ?: return false

    return imageScheme == backendScheme &&
        imageHost == backendHost &&
        imagePort == backendPort
}

private fun URI.effectivePort(): Int? = when {
    port >= 0 -> port
    scheme.equals("http", ignoreCase = true) -> 80
    scheme.equals("https", ignoreCase = true) -> 443
    else -> null
}
