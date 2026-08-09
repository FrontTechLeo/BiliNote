package com.bilinote.android

import kotlin.test.Test
import kotlin.test.assertEquals

class BootstrapTest {
    @Test
    fun applicationIdIsStable() {
        assertEquals("com.bilinote.android", BILINOTE_APPLICATION_ID)
    }
}
