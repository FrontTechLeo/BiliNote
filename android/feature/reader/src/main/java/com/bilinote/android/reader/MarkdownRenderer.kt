package com.bilinote.android.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.net.URI
import java.util.Locale

@Composable
fun MarkdownRenderer(
    markdown: String,
    backendUrl: String,
    modifier: Modifier = Modifier,
    onOpenTimeLink: (Int) -> Unit = {},
    accessToken: String? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        parseMarkdown(markdown).forEachIndexed { index, block ->
            MarkdownBlockView(block, backendUrl, index, onOpenTimeLink, accessToken)
        }
    }
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    data class Paragraph(val text: String) : MarkdownBlock

    data class UnorderedList(val items: List<String>) : MarkdownBlock

    data class OrderedList(val items: List<String>) : MarkdownBlock

    data class Code(val language: String?, val text: String) : MarkdownBlock

    data class Quote(val text: String) : MarkdownBlock
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    backendUrl: String,
    index: Int,
    onOpenTimeLink: (Int) -> Unit,
    accessToken: String?,
) {
    when (block) {
        is MarkdownBlock.Heading -> Text(
            text = block.text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineLarge
                2 -> MaterialTheme.typography.headlineMedium
                3 -> MaterialTheme.typography.headlineSmall
                4 -> MaterialTheme.typography.titleLarge
                5 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            modifier = Modifier.testTag("markdown-heading-${block.level}")
                .semantics { contentDescription = "Heading level ${block.level}: ${block.text}" },
        )

        is MarkdownBlock.Paragraph -> InlineMarkdown(block.text, backendUrl, "markdown-paragraph", onOpenTimeLink, accessToken)

        is MarkdownBlock.UnorderedList -> ListColumn(
            block.items,
            ordered = false,
            backendUrl = backendUrl,
            onOpenTimeLink = onOpenTimeLink,
            accessToken = accessToken,
        )

        is MarkdownBlock.OrderedList -> ListColumn(
            block.items,
            ordered = true,
            backendUrl = backendUrl,
            onOpenTimeLink = onOpenTimeLink,
            accessToken = accessToken,
        )

        is MarkdownBlock.Code -> Text(
            text = block.text,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("markdown-code-$index")
                .padding(12.dp),
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is MarkdownBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("markdown-quote-$index")
                .padding(start = 12.dp),
        ) {
            Text("▌", color = MaterialTheme.colorScheme.primary)
            InlineMarkdown(block.text, backendUrl, "markdown-quote-text", onOpenTimeLink, accessToken)
        }
    }
}

@Composable
private fun ListColumn(
    items: List<String>,
    ordered: Boolean,
    backendUrl: String,
    onOpenTimeLink: (Int) -> Unit,
    accessToken: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(if (ordered) "markdown-ordered-list" else "markdown-unordered-list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    modifier = Modifier.widthIn(min = 28.dp),
                )
                InlineMarkdown(item, backendUrl, "markdown-list-item", onOpenTimeLink, accessToken)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun InlineMarkdown(
    text: String,
    backendUrl: String,
    tag: String,
    onOpenTimeLink: (Int) -> Unit,
    accessToken: String?,
) {
    val uriHandler = LocalUriHandler.current
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        parseInline(text).forEach { part ->
            when (part) {
                is InlinePart.Text -> Text(part.value)
                is InlinePart.Link -> Text(
                    text = part.label,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { uriHandler.openUri(part.url) }
                        .semantics { contentDescription = "Open link ${part.label}" },
                )
                is InlinePart.TimeLink -> Text(
                    text = part.label,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .testTag("markdown-time-link-${part.seconds}")
                        .clickable { onOpenTimeLink(part.seconds) }
                        .semantics { contentDescription = "Open content at ${part.seconds} seconds" },
                )
                is InlinePart.Image -> MarkdownImage(
                    source = resolveImageUrl(part.url, backendUrl),
                    alt = part.alt,
                    backendUrl = backendUrl,
                    accessToken = accessToken,
                )
            }
        }
    }
}

private sealed interface InlinePart {
    data class Text(val value: String) : InlinePart

    data class Link(val label: String, val url: String) : InlinePart

    data class TimeLink(val label: String, val seconds: Int) : InlinePart

    data class Image(val alt: String, val url: String) : InlinePart
}

@Composable
private fun MarkdownImage(
    source: String,
    alt: String,
    backendUrl: String,
    accessToken: String?,
) {
    var retryCount by remember(source) { mutableIntStateOf(0) }
    var isLoading by remember(source, retryCount) { mutableStateOf(true) }
    var hasError by remember(source, retryCount) { mutableStateOf(false) }
    val context = LocalContext.current
    val imageRequest = remember(source, backendUrl, accessToken, retryCount) {
        ImageRequest.Builder(context)
            .data(source)
            .apply {
                accessToken?.trim()?.takeIf(String::isNotEmpty)
                    ?.takeIf { shouldAttachImageAuthorization(source, backendUrl) }
                    ?.let { token ->
                        addHeader("Authorization", "Bearer $token")
                    }
            }
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 360.dp)
            .testTag("markdown-image")
            .semantics { contentDescription = alt.ifBlank { "Screenshot" } },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = alt.ifBlank { "Screenshot" },
            modifier = Modifier.fillMaxWidth().alpha(if (hasError) 0f else 1f),
            onLoading = {
                isLoading = true
                hasError = false
            },
            onSuccess = {
                isLoading = false
                hasError = false
            },
            onError = {
                isLoading = false
                hasError = true
            },
        )
        if (isLoading && !hasError) CircularProgressIndicator(modifier = Modifier.size(32.dp))
        if (hasError) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("图片加载失败")
                OutlinedButton(
                    onClick = { retryCount++ },
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("markdown-image-retry")
                        .semantics { contentDescription = "Retry image loading" },
                ) { Text("重试") }
            }
        }
    }
}

private fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val lines = markdown.replace("\r\n", "\n").split('\n')
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
            paragraph.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val fence = Regex("^\\s*```\\s*([\\w+-]*)\\s*$").matchEntire(line)
        if (fence != null) {
            flushParagraph()
            val codeLines = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                codeLines += lines[index]
                index++
            }
            blocks += MarkdownBlock.Code(fence.groupValues[1].ifBlank { null }, codeLines.joinToString("\n"))
            index++
            continue
        }

        val heading = Regex("^\\s*(#{1,6})\\s+(.+?)\\s*#*\\s*$").matchEntire(line)
        if (heading != null) {
            flushParagraph()
            blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            index++
            continue
        }

        if (line.trimStart().startsWith(">")) {
            flushParagraph()
            val quoteLines = mutableListOf<String>()
            while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                quoteLines += lines[index].trimStart().removePrefix(">").trimStart()
                index++
            }
            blocks += MarkdownBlock.Quote(quoteLines.joinToString(" "))
            continue
        }

        val unordered = Regex("^\\s*[-+*]\\s+(.+)$").matchEntire(line)
        val ordered = Regex("^\\s*\\d+[.)]\\s+(.+)$").matchEntire(line)
        if (unordered != null || ordered != null) {
            flushParagraph()
            val orderedList = ordered != null
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val match = if (orderedList) {
                    Regex("^\\s*\\d+[.)]\\s+(.+)$").matchEntire(lines[index])
                } else {
                    Regex("^\\s*[-+*]\\s+(.+)$").matchEntire(lines[index])
                }
                if (match == null) break
                items += match.groupValues[1]
                index++
            }
            blocks += if (orderedList) MarkdownBlock.OrderedList(items) else MarkdownBlock.UnorderedList(items)
            continue
        }

        if (line.isBlank()) flushParagraph() else paragraph += line.trim()
        index++
    }
    flushParagraph()
    return blocks
}

private fun parseInline(text: String): List<InlinePart> {
    val timePattern = Regex("\\*?Content-\\[?(\\d{1,2}):(\\d{2})\\]?\\*?", RegexOption.IGNORE_CASE)
    val imagePattern = Regex("!\\[([^]]*)\\]\\(([^)]+)\\)")
    val linkPattern = Regex("\\[([^]]+)\\]\\(([^)]+)\\)")
    val matches = buildList {
        timePattern.findAll(text).forEach { match ->
            val minutes = match.groupValues[1].toInt()
            val seconds = match.groupValues[2].toInt()
            add(InlineMatch(match.range, InlinePart.TimeLink(match.value.trim('*'), minutes * 60 + seconds)))
        }
        imagePattern.findAll(text).forEach { match ->
            add(InlineMatch(match.range, InlinePart.Image(match.groupValues[1], match.groupValues[2])))
        }
        linkPattern.findAll(text).forEach { match ->
            add(InlineMatch(match.range, InlinePart.Link(match.groupValues[1], match.groupValues[2])))
        }
    }.sortedWith(compareBy<InlineMatch> { it.range.first }.thenByDescending { it.range.last })
    val result = mutableListOf<InlinePart>()
    var cursor = 0
    matches.forEach { match ->
        if (match.range.first < cursor) return@forEach
        if (match.range.first > cursor) result += InlinePart.Text(text.substring(cursor, match.range.first))
        result += match.part
        cursor = match.range.last + 1
    }
    if (cursor < text.length) result += InlinePart.Text(text.substring(cursor))
    return result.ifEmpty { listOf(InlinePart.Text(text)) }
}

private data class InlineMatch(val range: IntRange, val part: InlinePart)

internal fun resolveImageUrl(source: String, backendUrl: String): String {
    val value = source.trim()
    if (value.startsWith("data:", ignoreCase = true)) return value
    val parsed = runCatching { URI(value) }.getOrNull()
    if (parsed?.scheme?.lowercase(Locale.US) == "http" || parsed?.scheme?.lowercase(Locale.US) == "https") {
        return value
    }
    val base = URI(backendUrl.trimEnd('/') + "/")
    if (value.startsWith('/')) {
        return URI("${base.scheme}://${base.rawAuthority}$value").toString()
    }
    return base.resolve(value).toString()
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
