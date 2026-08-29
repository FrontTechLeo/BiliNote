package com.bilinote.android.reader

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

internal class MarkdownImageViewerState {
    var imageUrl: String? by mutableStateOf(null)
        private set

    fun open(url: String) {
        imageUrl = url
    }

    fun close() {
        imageUrl = null
    }
}

@Composable
internal fun MarkdownImageViewer(
    imageUrl: String,
    backendUrl: String,
    accessToken: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var retryGeneration by remember(imageUrl) { mutableIntStateOf(0) }
    var scale by remember(imageUrl) { mutableFloatStateOf(1f) }
    var offset by remember(imageUrl) { mutableStateOf(Offset.Zero) }
    var painterState by remember(imageUrl) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset += panChange
    }
    val request = remember(context, imageUrl, backendUrl, accessToken, retryGeneration) {
        fullScreenImageRequest(
            context = context,
            imageUrl = imageUrl,
            backendUrl = backendUrl,
            accessToken = accessToken,
            retryGeneration = retryGeneration,
        )
    }

    BackHandler(onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("markdown-image-viewer"),
    ) {
        AsyncImage(
            model = request,
            contentDescription = "全屏查看图片",
            contentScale = ContentScale.Fit,
            onState = { painterState = it },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .transformable(transformState),
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .testTag("markdown-image-viewer-close"),
        ) {
            Text("关闭", color = Color.White)
        }
        if (painterState is AsyncImagePainter.State.Loading || painterState is AsyncImagePainter.State.Empty) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }
        if (painterState is AsyncImagePainter.State.Error) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("图片加载失败", color = Color.White, style = MaterialTheme.typography.titleMedium)
                IconButton(
                    onClick = { retryGeneration++ },
                    modifier = Modifier.testTag("markdown-image-viewer-retry"),
                ) {
                    Text("重试", color = Color.White)
                }
            }
        }
    }
}

private fun fullScreenImageRequest(
    context: Context,
    imageUrl: String,
    backendUrl: String,
    accessToken: String?,
    retryGeneration: Int,
): ImageRequest = ImageRequest.Builder(context)
    .data(imageUrl)
    .memoryCacheKey("markdown-fullscreen:$retryGeneration:$imageUrl")
    .apply {
        accessToken?.trim()?.takeIf(String::isNotEmpty)
            ?.takeIf { shouldAttachImageAuthorization(imageUrl, backendUrl) }
            ?.let { token -> addHeader("Authorization", "Bearer $token") }
    }
    .build()
