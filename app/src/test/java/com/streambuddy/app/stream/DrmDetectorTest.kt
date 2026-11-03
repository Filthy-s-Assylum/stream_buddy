package com.streambuddy.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrmDetectorTest {

    @Test
    fun `plain playlist is not protected`() {
        assertNull(DrmDetector.detect("#EXTM3U\n#EXTINF:4,\na.ts"))
    }

    @Test
    fun `detects fairplay keyformat`() {
        val protection = DrmDetector.detect(
            """#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
#EXTINF:4,
a.ts""",
        )
        assertEquals("FairPlay", protection?.scheme)
    }

    @Test
    fun `detects widevine keyformat`() {
        val protection = DrmDetector.detect(
            """#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="data:text/plain;base64,AAA",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
#EXTINF:4,
a.ts""",
        )
        assertEquals("Widevine", protection?.scheme)
    }

    @Test
    fun `detects playready session key`() {
        val protection = DrmDetector.detect(
            """#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="https://k",KEYFORMAT="urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"""",
        )
        assertEquals("PlayReady", protection?.scheme)
    }

    @Test
    fun `sample aes without keyformat is treated as fairplay`() {
        val protection = DrmDetector.detect(
            """#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://abc"
#EXTINF:4,
a.ts""",
        )
        assertEquals("FairPlay", protection?.scheme)
    }

    @Test
    fun `aes-128 stays cacheable`() {
        assertNull(
            DrmDetector.detect(
                """#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="https://keys.test/k"
#EXTINF:4,
a.ts""",
            ),
        )
    }

    @Test
    fun `method none is ignored`() {
        assertNull(
            DrmDetector.detect(
                """#EXTM3U
#EXT-X-KEY:METHOD=NONE
#EXTINF:4,
a.ts""",
            ),
        )
    }

    @Test
    fun `detects dash widevine and cenc`() {
        assertEquals(
            "Widevine",
            DrmDetector.detect(
                """<MPD><ContentProtection schemeIdUri="urn:uuid:EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED"/></MPD>""",
            )?.scheme,
        )
        assertEquals(
            "CENC",
            DrmDetector.detect("""<MPD><ContentProtection cenc:default_KID="abc"/></MPD>""")?.scheme,
        )
    }
}
