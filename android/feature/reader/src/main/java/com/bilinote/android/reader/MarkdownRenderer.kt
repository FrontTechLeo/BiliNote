package com.bilinote.android.reader

import android.content.Context
import android.net.Uri
import android.text.Spanned
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.Coil
import coil.ImageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.coil.CoilImagesPlugin
import io.noties.markwon.image.destination.ImageDestinationProcessor
import io.noties.markwon.movement.MovementMethodPlugin
import io.noties.markwon.core.MarkwonTheme
import kotlin.math.roundToInt
import java.net.URI
import java.util.Locale

@Composable
fun MarkdownRenderer(
    markdown: String,
    backendUrl: String,
    modifier: Modifier = Modifier,
    onOpenTimeLink: (Int) -> Unit = {},
    accessToken: String? = null,
    initialScrollOffset: Int = 0,
    retryGeneration: Int = 0,
    onScrollChanged: (offset: Int, maxOffset: Int) -> Unit = { _, _ -> },
    onRestored: () -> Unit = {},
) {
    val context = LocalContext.current
    val bodyColor = MaterialTheme.colorScheme.onBackground.toArgb()
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val codeTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val quoteColor = MaterialTheme.colorScheme.outline.toArgb()
    var imageRetryGeneration by remember(backendUrl, accessToken) { mutableIntStateOf(0) }
    var imageLoadFailed by remember(backendUrl, accessToken) { mutableStateOf(false) }
    val effectiveRetryGeneration = retryGeneration + imageRetryGeneration
    val imageTracker = remember(backendUrl, accessToken, effectiveRetryGeneration) {
        ImageLoadTracker(
            context = context,
            backendUrl = backendUrl,
            accessToken = accessToken,
            retryGeneration = effectiveRetryGeneration,
            onFailure = { imageLoadFailed = true },
        )
    }
    val markwon = remember(
        context,
        backendUrl,
        accessToken,
        effectiveRetryGeneration,
        bodyColor,
        backgroundColor,
        linkColor,
        codeTextColor,
        codeBackgroundColor,
        quoteColor,
        onOpenTimeLink,
        imageTracker,
    ) {
        createMarkwon(
            context = context,
            backendUrl = backendUrl,
            imageTracker = imageTracker,
            bodyColor = bodyColor,
            backgroundColor = backgroundColor,
            linkColor = linkColor,
            codeTextColor = codeTextColor,
            codeBackgroundColor = codeBackgroundColor,
            quoteColor = quoteColor,
            onOpenTimeLink = onOpenTimeLink,
        )
    }

    val imageViewerState = remember { MarkdownImageViewerState() }
    Box(modifier = modifier.fillMaxSize().testTag("markdown-surface")) {
        Column(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("markdown-native-scroll"),
                factory = { viewContext ->
                    createMarkdownScrollView(viewContext).also { scrollView ->
                        val state = scrollView.tag as MarkdownScrollState
                        state.onScrollChanged = onScrollChanged
                        state.onRestored = onRestored
                        state.onImageClick = { destination ->
                            imageViewerState.open(resolveImageUrl(destination, backendUrl))
                        }
                    }
                },
                update = { scrollView ->
                    val state = scrollView.tag as MarkdownScrollState
                    state.onScrollChanged = onScrollChanged
                    state.onRestored = onRestored
                    state.onImageClick = { destination ->
                        imageViewerState.open(resolveImageUrl(destination, backendUrl))
                    }
                    val renderedMarkdown = normalizeBiliNoteTimeLinks(markdown)
                    if (
                        state.lastMarkdown != renderedMarkdown ||
                        state.lastRetryGeneration != effectiveRetryGeneration ||
                        state.lastMarkwon !== markwon
                    ) {
                        state.lastMarkdown = renderedMarkdown
                        state.lastRetryGeneration = effectiveRetryGeneration
                        state.lastMarkwon = markwon
                        val textView = scrollView.getChildAt(0) as TextView
                        markwon.setMarkdown(textView, renderedMarkdown)
                        scrollView.post {
                            val maxOffset = (textView.height - scrollView.height).coerceAtLeast(0)
                            scrollView.scrollTo(0, initialScrollOffset.coerceIn(0, maxOffset))
                            state.onRestored()
                        }
                    }
                },
            )
            if (imageLoadFailed) {
                OutlinedButton(
                    onClick = {
                        imageLoadFailed = false
                        imageRetryGeneration++
                    },
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .testTag("markdown-image-retry"),
                ) {
                    Text("图片加载失败，重试")
                }
            }
        }
        imageViewerState.imageUrl?.let { imageUrl ->
            MarkdownImageViewer(
                imageUrl = imageUrl,
                backendUrl = backendUrl,
                accessToken = accessToken,
                onClose = imageViewerState::close,
            )
        }
    }
}

private fun createMarkdownScrollView(context: Context): ScrollView {
    val density = context.resources.displayMetrics.density
    val textView = TextView(context).apply {
        tag = "markdown-text-view"
        setTextIsSelectable(true)
        includeFontPadding = true
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        setLineSpacing(4f * density, 1f)
        setPadding(dp(20, density), dp(16, density), dp(20, density), dp(28, density))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    return ScrollView(context).apply {
        tag = MarkdownScrollState()
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(textView)
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        setOnTouchListener { view, event ->
            handleMarkdownImageTouch(view as ScrollView, event, touchSlop)
        }
        setOnScrollChangeListener { view, _, scrollY, _, _ ->
            val scrollView = view as ScrollView
            val state = scrollView.tag as MarkdownScrollState
            val child = scrollView.getChildAt(0)
            val maxOffset = (child?.height.orZero() - scrollView.height).coerceAtLeast(0)
            state.onScrollChanged(scrollY, maxOffset)
        }
    }
}

private fun createMarkwon(
    context: Context,
    backendUrl: String,
    imageTracker: ImageLoadTracker,
    bodyColor: Int,
    backgroundColor: Int,
    linkColor: Int,
    codeTextColor: Int,
    codeBackgroundColor: Int,
    quoteColor: Int,
    onOpenTimeLink: (Int) -> Unit,
): Markwon {
    val density = context.resources.displayMetrics.density
    return Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(TaskListPlugin.create(context))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
        .usePlugin(CoilImagesPlugin.create(imageTracker, imageTracker.imageLoader))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder
                    .linkResolver(BiliNoteLinkResolver(onOpenTimeLink))
                    .imageDestinationProcessor(BiliNoteImageDestinationProcessor(backendUrl))
            }

            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder
                    .linkColor(linkColor)
                    .isLinkUnderlined(false)
                    .blockMargin(dp(18, density))
                    .blockQuoteColor(quoteColor)
                    .codeTextColor(codeTextColor)
                    .codeBlockTextColor(codeTextColor)
                    .codeBackgroundColor(codeBackgroundColor)
                    .codeBlockBackgroundColor(codeBackgroundColor)
            }

            override fun beforeSetText(textView: TextView, markdown: Spanned) {
                textView.setTextColor(bodyColor)
                textView.setBackgroundColor(backgroundColor)
                textView.movementMethod = TableAwareMovementMethod.create()
            }
        })
        .build()
}

private class MarkdownScrollState {
    var lastMarkdown: String? = null
    var lastRetryGeneration: Int = -1
    var lastMarkwon: Markwon? = null
    var onScrollChanged: (Int, Int) -> Unit = { _, _ -> }
    var onRestored: () -> Unit = {}
    var onImageClick: (String) -> Unit = {}
    var imageTouchDestination: String? = null
    var imageTouchX: Float = 0f
    var imageTouchY: Float = 0f
}

private fun handleMarkdownImageTouch(
    scrollView: ScrollView,
    event: MotionEvent,
    touchSlop: Int,
): Boolean {
    val state = scrollView.tag as MarkdownScrollState
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            state.imageTouchDestination = imageDestinationAt(scrollView, event.x, event.y)
            state.imageTouchX = event.x
            state.imageTouchY = event.y
        }
        MotionEvent.ACTION_MOVE -> {
            if (state.imageTouchDestination != null &&
                distanceSquared(state.imageTouchX, state.imageTouchY, event.x, event.y) > touchSlop * touchSlop
            ) {
                state.imageTouchDestination = null
            }
        }
        MotionEvent.ACTION_UP -> {
            val destination = state.imageTouchDestination
            state.imageTouchDestination = null
            if (destination != null &&
                distanceSquared(state.imageTouchX, state.imageTouchY, event.x, event.y) <= touchSlop * touchSlop
            ) {
                state.onImageClick(destination)
                return true
            }
        }
        MotionEvent.ACTION_CANCEL -> state.imageTouchDestination = null
    }
    return false
}

private fun imageDestinationAt(scrollView: ScrollView, x: Float, y: Float): String? {
    val textView = scrollView.getChildAt(0) as? TextView ?: return null
    val layout = textView.layout ?: return null
    val contentY = (y + scrollView.scrollY - textView.top - textView.paddingTop).toInt()
    if (contentY !in 0..layout.height) return null
    val contentX = x - textView.left - textView.paddingLeft
    val line = layout.getLineForVertical(contentY)
    val offset = layout.getOffsetForHorizontal(line, contentX)
    val text = textView.text as? Spanned ?: return null
    val start = (offset - 1).coerceAtLeast(0)
    val end = (offset + 1).coerceAtMost(text.length)
    return text.getSpans(start, end, AsyncDrawableSpan::class.java)
        .firstOrNull()
        ?.drawable
        ?.destination
}

private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    return dx * dx + dy * dy
}

private class ImageLoadTracker(
    private val context: Context,
    private val backendUrl: String,
    private val accessToken: String?,
    private val retryGeneration: Int,
    private val onFailure: () -> Unit,
) : CoilImagesPlugin.CoilStore {
    val imageLoader: ImageLoader = Coil.imageLoader(context)

    override fun load(drawable: AsyncDrawable): ImageRequest {
        val destination = drawable.destination
        return ImageRequest.Builder(context)
            .data(destination)
            .memoryCacheKey("markwon:$retryGeneration:$destination")
            .apply {
                accessToken?.trim()?.takeIf(String::isNotEmpty)
                    ?.takeIf { shouldAttachImageAuthorization(destination, backendUrl) }
                    ?.let { token -> addHeader("Authorization", "Bearer $token") }
            }
            .listener(
                onError = { _, _ -> onFailure() },
            )
            .build()
    }

    override fun cancel(disposable: Disposable) {
        disposable.dispose()
    }
}

private class BiliNoteImageDestinationProcessor(
    private val backendUrl: String,
) : ImageDestinationProcessor() {
    override fun process(destination: String): String = resolveImageUrl(destination, backendUrl)
}

internal class BiliNoteLinkResolver(
    private val onOpenTimeLink: (Int) -> Unit,
    private val fallback: LinkResolver = LinkResolverDef(),
) : LinkResolver {
    override fun resolve(view: View, link: String) {
        val uri = Uri.parse(link)
        if (uri.scheme.equals("bilinote-time", ignoreCase = true)) {
            uri.host?.toIntOrNull()?.takeIf { it >= 0 }?.let(onOpenTimeLink)
            return
        }
        fallback.resolve(view, link)
    }
}

private fun dp(value: Int, density: Float): Int = (value * density).roundToInt()

private fun Int?.orZero(): Int = this ?: 0

internal fun resolveImageUrl(source: String, backendUrl: String): String {
    val value = source.trim()
    if (value.startsWith("data:", ignoreCase = true)) return value
    val parsed = runCatching { URI(value) }.getOrNull()
    val scheme = parsed?.scheme?.lowercase(Locale.US)
    if (scheme == "http" || scheme == "https") return value
    val base = runCatching { URI(backendUrl.trimEnd('/') + "/") }.getOrNull() ?: return value
    if (value.startsWith('/')) {
        return runCatching { URI("${base.scheme}://${base.rawAuthority}$value").toString() }.getOrDefault(value)
    }
    return runCatching { base.resolve(value).toString() }.getOrDefault(value)
}
