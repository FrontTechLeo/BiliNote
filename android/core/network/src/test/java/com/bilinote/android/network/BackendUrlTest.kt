package com.bilinote.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackendUrlTest {
    @Test fun `normalizes whitespace and trailing slash`() {
        assertEquals("https://example.com/api", BackendUrl.normalize("  https://example.com/api///  "))
    }

    @Test fun `accepts localhost http`() {
        assertEquals("http://localhost:8080", BackendUrl.normalize("http://localhost:8080/"))
    }

    @Test fun `rejects blank invalid scheme and missing host`() {
        listOf("", "ftp://example.com", "/api", "http://").forEach {
            assertThrows(IllegalArgumentException::class.java) { BackendUrl.normalize(it) }
        }
    }
}
