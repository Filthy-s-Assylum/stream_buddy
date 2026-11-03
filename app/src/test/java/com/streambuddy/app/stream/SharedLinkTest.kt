package com.streambuddy.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLinkTest {

    private val hexHash = "c12fe1c06bba254a9dc9178d2d8b4b45a3f0f3c9"
    private val magnet = "magnet:?xt=urn:btih:$hexHash&dn=Clip"

    private val actionView = "android.intent.action.VIEW"
    private val actionSend = "android.intent.action.SEND"

    @Test
    fun sharedTextWithUrlBecomesAStream() {
        val target = SharedLink.parse(
            action = actionSend,
            dataString = null,
            sharedText = "Check this out https://cdn.example.com/live/master.m3u8 enjoy",
        )
        assertEquals(SharedLink.Target.Stream("https://cdn.example.com/live/master.m3u8"), target)
    }

    @Test
    fun trailingPunctuationIsDropped() {
        val target = SharedLink.parse(
            action = actionSend,
            dataString = null,
            sharedText = "Watch (https://example.com/clip.mp4).",
        )
        assertEquals(SharedLink.Target.Stream("https://example.com/clip.mp4"), target)
    }

    @Test
    fun firstUsableUrlWins() {
        val target = SharedLink.parse(
            action = actionSend,
            dataString = null,
            sharedText = "https://example.com/a.m3u8 and also https://example.com/b.m3u8",
        )
        assertEquals(SharedLink.Target.Stream("https://example.com/a.m3u8"), target)
    }

    @Test
    fun textWithoutUrlIsIgnored() {
        assertEquals(
            SharedLink.Target.Ignore,
            SharedLink.parse(actionSend, null, "just a message with no link"),
        )
    }

    @Test
    fun nullPayloadIsIgnored() {
        assertEquals(SharedLink.Target.Ignore, SharedLink.parse(null, null, null))
    }

    @Test
    fun deepLinksMapToTabs() {
        assertEquals(
            SharedLink.Target.Tab("queue"),
            SharedLink.parse(actionView, "streambuddy://queue", null),
        )
        assertEquals(
            SharedLink.Target.Tab("settings"),
            SharedLink.parse(actionView, "streambuddy://settings?from=shortcut", null),
        )
    }

    @Test
    fun unknownDeepLinkHostIsIgnored() {
        assertEquals(SharedLink.Target.Ignore, SharedLink.parse(actionView, "streambuddy://bogus", null))
        assertNull(SharedLink.deepLink("https://streambuddy.app/queue"))
        assertNull(SharedLink.deepLink("streambuddy://"))
    }

    @Test
    fun viewIntentWithMediaUrlIsAStream() {
        assertEquals(
            SharedLink.Target.Stream("https://example.com/movie.mkv"),
            SharedLink.parse(actionView, "https://example.com/movie.mkv", null),
        )
    }

    @Test
    fun viewIntentWithNonMediaUrlIsIgnored() {
        assertEquals(SharedLink.Target.Ignore, SharedLink.parse(actionView, "file:///sdcard/x.m3u8", null))
    }

    @Test
    fun extractUrlSkipsInvalidCandidates() {
        assertNull(SharedLink.extractUrl("no links here"))
        assertEquals("https://example.com/x.mp4", SharedLink.extractUrl("https://example.com/x.mp4"))
    }

    @Test
    fun knownTabsMatchTheNavGraph() {
        assertTrue(SharedLink.KNOWN_TABS.containsAll(setOf("apps", "queue", "player", "settings")))
    }

    @Test
    fun aSharedMagnetBecomesATorrentTarget() {
        val target = SharedLink.parse(
            action = actionSend,
            dataString = null,
            sharedText = "Try this $magnet it is fast",
        )
        assertEquals(SharedLink.Target.Magnet(magnet), target)
    }

    @Test
    fun aMagnetViewIntentBecomesATorrentTarget() {
        val target = SharedLink.parse(action = actionView, dataString = magnet, sharedText = null)
        assertEquals(SharedLink.Target.Magnet(magnet), target)
    }

    @Test
    fun magnetExtractionIgnoresProseWithoutAHash() {
        assertNull(SharedLink.extractMagnet("I love magnets:? they are fun"))
    }

    @Test
    fun URLsStillWinWhenNoMagnetIsPresent() {
        val target = SharedLink.parse(
            action = actionSend,
            dataString = null,
            sharedText = "https://example.com/a.m3u8",
        )
        assertEquals(SharedLink.Target.Stream("https://example.com/a.m3u8"), target)
    }
}
