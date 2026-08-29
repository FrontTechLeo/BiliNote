package com.bilinote.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownImageViewerStateTest {
    @Test
    fun `opening and closing image viewer preserves selected image state`() {
        val state = MarkdownImageViewerState()

        state.open("https://service-a/static/shot.png")
        assertEquals("https://service-a/static/shot.png", state.imageUrl)

        state.close()
        assertNull(state.imageUrl)
    }
}
