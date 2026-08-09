package com.bilinote.android.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppDestinationTest {
    @Test
    fun fixedDestinationsUseStableRoutes() {
        assertEquals("home", AppDestination.Home.route)
        assertEquals("batch", AppDestination.Batch.route)
        assertEquals("library", AppDestination.Library.route)
        assertEquals("settings", AppDestination.Settings.route)
    }

    @Test
    fun parameterRoutesEncodeAndParseIds() {
        val reader = AppDestination.Reader("task/with spaces?&=")
        val batchDetail = AppDestination.BatchDetail("batch/中文?&=")

        assertTrue(reader.route.startsWith("reader/"))
        assertTrue(batchDetail.route.startsWith("batch-detail/"))
        assertEquals(reader, AppDestination.fromRoute(reader.route))
        assertEquals(batchDetail, AppDestination.fromRoute(batchDetail.route))
    }

    @Test
    fun specialCharactersRemainInTheirOriginalIds() {
        val id = "a+b/c?d=e&f 中文#%"

        val destination = AppDestination.Reader(id)

        assertEquals(destination, AppDestination.fromRoute(destination.route))
        assertTrue(destination.route.contains("%2B"))
        assertTrue(destination.route.contains("%2F"))
        assertTrue(destination.route.contains("%20"))
    }

    @Test
    fun malformedOrUnknownRoutesReturnNull() {
        assertNull(AppDestination.fromRoute("unknown"))
        assertNull(AppDestination.fromRoute("reader/"))
        assertNull(AppDestination.fromRoute("reader/%ZZ"))
        assertNull(AppDestination.fromRoute("batch-detail/"))
    }

    @Test
    fun routeParserRejectsQueriesFragmentsAndMultipleRawSegments() {
        assertNull(AppDestination.fromRoute("reader/task?next=1"))
        assertNull(AppDestination.fromRoute("reader/task#section"))
        assertNull(AppDestination.fromRoute("reader/task/child"))
        assertNull(AppDestination.fromRoute("batch-detail/batch?next=1"))
        assertNull(AppDestination.fromRoute("batch-detail/batch#section"))
        assertNull(AppDestination.fromRoute("batch-detail/batch/child"))
    }

    @Test
    fun encodedReservedCharactersRemainValidSingleSegments() {
        assertEquals(
            AppDestination.Reader("task/part?section#top"),
            AppDestination.fromRoute("reader/task%2Fpart%3Fsection%23top"),
        )
        assertEquals(
            AppDestination.BatchDetail("batch/part?section#top"),
            AppDestination.fromRoute("batch-detail/batch%2Fpart%3Fsection%23top"),
        )
    }
}
