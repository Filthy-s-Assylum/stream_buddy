package com.streambuddy.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LoopbackServerTest {

    @Test
    fun `serves authenticated range requests and rejects bad tokens or invalid ranges`() {
        val mediaFile = File.createTempFile("sb-loopback-media", ".mp4").apply {
            writeBytes("0123456789abcdef".toByteArray())
            deleteOnExit()
        }
        val subFile = File.createTempFile("sb-loopback-sub", ".srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:01,000\nHello\n")
            deleteOnExit()
        }
        val server = LoopbackServer(
            catalog = object : FileCatalog {
                override fun media(): ServedFile = ServedFile(mediaFile, mediaFile.length(), "video/mp4")
                override fun subtitle(index: Int): ServedFile? = if (index == 0) {
                    ServedFile(subFile, subFile.length(), "application/x-subrip; charset=utf-8")
                } else {
                    null
                }
            },
            token = "0123456789abcdef0123456789abcdef",
        )

        val base = server.start()
        try {
            assertTrue(base.startsWith("http://127.0.0.1:"))

            val badToken = (URL(base.replace("0123", "ffff") + "/media").openConnection() as HttpURLConnection)
            assertEquals(404, badToken.responseCode)
            badToken.disconnect()

            val health = (URL("$base/health").openConnection() as HttpURLConnection)
            assertEquals(200, health.responseCode)
            assertEquals("ok", health.inputStream.bufferedReader().readText())
            health.disconnect()

            val partial = (URL(server.mediaUrl()).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=2-5")
            }
            assertEquals(206, partial.responseCode)
            assertEquals("bytes 2-5/16", partial.getHeaderField("Content-Range"))
            assertEquals("2345", partial.inputStream.bufferedReader().readText())
            partial.disconnect()

            val invalidRange = (URL(server.mediaUrl()).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=50-60")
            }
            assertEquals(416, invalidRange.responseCode)
            invalidRange.disconnect()

            val sub = (URL(server.subtitleUrl(0)).openConnection() as HttpURLConnection)
            assertEquals(200, sub.responseCode)
            assertTrue(sub.getHeaderField("Content-Type").startsWith("application/x-subrip"))
            sub.disconnect()

            val missingSub = (URL(server.subtitleUrl(5)).openConnection() as HttpURLConnection)
            assertEquals(404, missingSub.responseCode)
            missingSub.disconnect()
        } finally {
            server.stop()
            mediaFile.delete()
            subFile.delete()
        }
    }
}
