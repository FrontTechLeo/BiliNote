package com.bilinote.android.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class ImageCompressionLevel(
    val wireValue: String,
    val label: String,
) {
    HIGH("high", "高压缩（JPEG/720p）"),
    MEDIUM("medium", "中等（JPEG）"),
    ORIGINAL("original", "最高/原图（PNG）"),
    ;

    companion object {
        fun fromWireValue(value: String?): ImageCompressionLevel {
            return when (value?.trim()?.lowercase()) {
                "highest", "lossless", "none", ORIGINAL.wireValue -> ORIGINAL
                MEDIUM.wireValue -> MEDIUM
                else -> HIGH
            }
        }
    }
}

fun normalizeImageCompression(value: String?): String =
    ImageCompressionLevel.fromWireValue(value).wireValue

@Composable
fun ImageCompressionSelector(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = ImageCompressionLevel.fromWireValue(value)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("图片压缩级别", style = MaterialTheme.typography.titleMedium)
        ImageCompressionLevel.entries.forEach { level ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .selectable(
                        selected = selected == level,
                        onClick = { onValueChange(level.wireValue) },
                        role = Role.RadioButton,
                    )
                    .semantics { role = Role.RadioButton },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == level,
                    onClick = null,
                )
                Text(level.label)
            }
        }
    }
}
