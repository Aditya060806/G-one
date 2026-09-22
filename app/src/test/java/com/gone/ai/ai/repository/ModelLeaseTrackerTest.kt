package com.gone.ai.ai.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelLeaseTrackerTest {

    private val grace = 60_000L

    /**
     * The regression this replaces: monitoring used to unload the shared model when it
     * stopped, even while chat was open.
     */
    @Test
    fun `the model is kept while any holder remains`() = runTest {
        var releases = 0
        val tracker = ModelLeaseTracker(backgroundScope, grace) { releases++ }

        val chat = tracker.acquire("chat")
        val monitoring = tracker.acquire("health-monitoring")

        monitoring.close()
        advanceTimeBy(grace * 3)
        assertEquals(0, releases)
        assertEquals(listOf("chat"), tracker.holderNames)

        chat.close()
        advanceTimeBy(grace - 1_000)
        assertEquals("still inside the grace period", 0, releases)

        advanceTimeBy(2_000)
        assertEquals(1, releases)
        assertTrue(tracker.isIdle)
    }

    /** Navigating from one screen to the next must not reload a 1 GB model. */
    @Test
    fun `reacquiring within the grace period cancels the release`() = runTest {
        var releases = 0
        val tracker = ModelLeaseTracker(backgroundScope, grace) { releases++ }

        tracker.acquire("pdf-summary").close()
        advanceTimeBy(grace / 2)
        val next = tracker.acquire("ocr")
        advanceTimeBy(grace * 2)

        assertEquals(0, releases)
        assertFalse(next.isClosed)
    }

    @Test
    fun `closing a lease twice does not release another holder's claim`() = runTest {
        var releases = 0
        val tracker = ModelLeaseTracker(backgroundScope, grace) { releases++ }

        val first = tracker.acquire("a")
        tracker.acquire("b")
        first.close()
        first.close()
        advanceTimeBy(grace * 2)

        assertEquals(0, releases)
        assertEquals(listOf("b"), tracker.holderNames)
    }
}
