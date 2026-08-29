package com.bilinote.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLinkSupportTest {
    @Test
    fun `time marker becomes a callback link`() {
        assertEquals(
            "[Content-01:23](bilinote-time://83)",
            normalizeBiliNoteTimeLinks("*Content-[01:23]*"),
        )
    }

    @Test
    fun `time marker keeps ordinary markdown around it`() {
        assertEquals(
            "Read [Content-02:05](bilinote-time://125) now",
            normalizeBiliNoteTimeLinks("Read Content-[02:05] now"),
        )
    }

    @Test
    fun `invalid time marker remains unchanged`() {
        assertEquals("Content-[01:75]", normalizeBiliNoteTimeLinks("Content-[01:75]"))
    }

    @Test
    fun `backend image auth is limited to same origin`() {
        assertTrue(shouldAttachImageAuthorization("http://host:8483/static/a.png", "http://host:8483"))
        assertFalse(shouldAttachImageAuthorization("http://other:8483/static/a.png", "http://host:8483"))
        assertFalse(shouldAttachImageAuthorization("not a url", "http://host:8483"))
    }

}
