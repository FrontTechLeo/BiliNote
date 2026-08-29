package com.bilinote.android.feature.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchUiModelsTest {
    @Test
    fun `default config matches batch defaults`() {
        assertEquals("medium", defaultBatchConfig.quality)
        assertEquals("tutorial", defaultBatchConfig.style)
        assertEquals(6, defaultBatchConfig.videoInterval)
        assertEquals(listOf(2, 2), defaultBatchConfig.gridSize)
        assertEquals(listOf("toc", "summary"), defaultBatchConfig.format)
        assertEquals("high", defaultBatchConfig.imageCompression)
    }

    @Test
    fun `format derives link and screenshot and removes screenshot when video understanding is disabled`() {
        val disabled = BatchConfig(
            videoUnderstanding = false,
            format = listOf("toc", "link", "screenshot", "link"),
        )
        assertTrue(disabled.link)
        assertFalse(disabled.screenshot)
        assertEquals(listOf("toc", "link"), disabled.normalized().format)

        val enabled = disabled.copy(videoUnderstanding = true).normalized()
        assertTrue(enabled.screenshot)
        assertEquals(listOf("toc", "link", "screenshot"), enabled.format)
    }

    @Test
    fun `natural sort compares numeric portions and preserves stable order`() {
        assertEquals(
            listOf("clip1.mp4", "clip2.mp4", "clip10.mp4"),
            naturalSort(listOf("clip10.mp4", "clip2.mp4", "clip1.mp4")),
        )
    }

    @Test
    fun `natural sort continues after equal numeric portions with leading zeros`() {
        assertEquals(
            listOf("file01-part2.mp4", "file1-part10.mp4"),
            naturalSort(listOf("file1-part10.mp4", "file01-part2.mp4")),
        )
    }

    @Test
    fun `duplicate filtering keeps first nonblank source url`() {
        val items = listOf(
            BatchPreviewItem(id = "1", title = "first", sourceUrl = " https://example.com/a "),
            BatchPreviewItem(id = "2", title = "duplicate", sourceUrl = "https://example.com/a"),
            BatchPreviewItem(id = "3", title = "blank one", sourceUrl = ""),
            BatchPreviewItem(id = "4", title = "blank two", sourceUrl = ""),
        )

        assertEquals(
            listOf("1", "3", "4"),
            filterDuplicatePreviewItems(items).map { it.id },
        )
    }

    @Test
    fun `preview mapping removes duplicate source urls and updates duplicate count`() {
        val preview = com.bilinote.android.network.dto.BatchPreview(
            duplicateCount = 1,
            items = listOf(
                com.bilinote.android.network.dto.BatchPreviewItem(id = "1", sourceUrl = " https://example.com/a "),
                com.bilinote.android.network.dto.BatchPreviewItem(id = "2", sourceUrl = "https://example.com/a"),
                com.bilinote.android.network.dto.BatchPreviewItem(id = "3", sourceUrl = "https://example.com/b"),
            ),
        ).toUiModel()

        assertEquals(listOf("1", "3"), preview.items.map { it.id })
        assertEquals(2, preview.duplicateCount)
    }

    @Test
    fun `config mapping derives link and screenshot from format`() {
        val mapped = com.bilinote.android.network.dto.BatchConfig(
            link = true,
            screenshot = true,
            format = listOf("toc"),
            videoUnderstanding = false,
        ).toUiModel()

        assertFalse(mapped.link)
        assertFalse(mapped.screenshot)
        assertEquals(false, mapped.toDto().link)
        assertEquals(false, mapped.toDto().screenshot)
    }

    @Test
    fun `normalization overrides cannot contradict format`() {
        val normalized = BatchConfig(
            format = listOf("toc"),
            linkOverride = true,
            screenshotOverride = true,
            videoUnderstanding = true,
        ).normalized()

        assertFalse(normalized.link)
        assertFalse(normalized.screenshot)
        assertEquals(listOf("toc"), normalized.format)
    }

    @Test
    fun `image compression is normalized and mapped in both directions`() {
        assertEquals("medium", BatchConfig(imageCompression = "MEDIUM").normalized().imageCompression)
        assertEquals("original", BatchConfig(imageCompression = "original").toDto().imageCompression)
        assertEquals("medium", com.bilinote.android.network.dto.BatchConfig(imageCompression = "medium").toUiModel().imageCompression)
        assertEquals("high", com.bilinote.android.network.dto.BatchConfig(imageCompression = null).toUiModel().imageCompression)
    }
}
