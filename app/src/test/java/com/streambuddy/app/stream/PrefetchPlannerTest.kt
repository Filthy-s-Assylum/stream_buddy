package com.streambuddy.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchPlannerTest {

    @Test
    fun `vod caches from the start of the playlist`() {
        val playlist = playlistOf(List(5) { 6_000L })

        val plan = PrefetchPlanner.plan(playlist, depthMs = 12_000L)

        assertEquals(2, plan.size)
        assertEquals("https://cdn.test/seg0.ts", plan[0].url)
    }

    @Test
    fun `vod stops at the end of a short playlist`() {
        val playlist = playlistOf(List(5) { 6_000L })

        assertEquals(5, PrefetchPlanner.plan(playlist, depthMs = 600_000L).size)
    }

    @Test
    fun `live caches the newest segments`() {
        val playlist = playlistOf(List(10) { 4_000L }, live = true)

        val plan = PrefetchPlanner.plan(playlist, depthMs = 8_000L)

        assertEquals(2, plan.size)
        assertEquals("https://cdn.test/seg8.ts", plan.first().url)
        assertEquals("https://cdn.test/seg9.ts", plan.last().url)
    }

    @Test
    fun `initialization segment comes first`() {
        val init = HlsSegment("https://cdn.test/init.mp4", 0L, 0L, 720L, isInitialization = true)
        val playlist = playlistOf(listOf(6_000L), init = init)

        val plan = PrefetchPlanner.plan(playlist, depthMs = 6_000L)

        assertEquals(2, plan.size)
        assertEquals(init, plan.first())
    }

    @Test
    fun `respects the segment cap`() {
        val playlist = playlistOf(List(50) { 1_000L })

        val plan = PrefetchPlanner.plan(playlist, depthMs = 600_000L, maxSegments = 5)

        assertEquals(5, plan.size)
    }

    @Test
    fun `empty playlist yields empty plan`() {
        assertTrue(PrefetchPlanner.plan(playlistOf(emptyList()), 60_000L).isEmpty())
    }

    private fun playlistOf(
        durations: List<Long>,
        live: Boolean = false,
        init: HlsSegment? = null,
    ): HlsMediaPlaylist = HlsMediaPlaylist(
        segments = durations.mapIndexed { index, duration ->
            HlsSegment(url = "https://cdn.test/seg$index.ts", durationMs = duration)
        },
        initializationSegment = init,
        totalDurationMs = durations.sum(),
        targetDurationMs = durations.maxOrNull() ?: 0L,
        isLive = live,
    )
}
