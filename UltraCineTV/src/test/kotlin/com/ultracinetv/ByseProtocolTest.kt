package com.ultracinetv

import org.junit.Assert.*
import org.junit.Test

class ByseProtocolTest {
    @Test fun publishedChallengeMatchesAcceptedSolution() {
        assertTrue(ByseProtocol.verify("dd4fcfb7b02740980e9f9e82ad14b3b4", 20231, 16))
        assertFalse(ByseProtocol.verify("dd4fcfb7b02740980e9f9e82ad14b3b4", 20230, 16))
    }

    @Test fun versionSelectsPublishedKeyPositions() {
        assertEquals(listOf(16, 13), ByseProtocol.keyIndexes(17, 30))
        assertEquals(listOf(0, 29), ByseProtocol.keyIndexes(1, 30))
        assertEquals(listOf(0, 1), ByseProtocol.keyIndexes(null, 2))
    }

    @Test fun rejectsUnknownVersion() {
        assertThrows(IllegalArgumentException::class.java) { ByseProtocol.keyIndexes(21, 30) }
    }

    @Test fun extractsCodeBeforeOptionalSlug() {
        assertEquals("9rsh4wax3y1f", ByseProtocol.code("https://embedplaybyse.top/e/9rsh4wax3y1f/a-film"))
        assertEquals("9rsh4wax3y1f", ByseProtocol.code("https://n1mwq.org/fjp/9rsh4wax3y1f"))
    }

    @Test fun ignoresTrickPlayTracksWhenReadingMasterResolution() {
        val manifest = """#EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3414001,RESOLUTION=1920x1080
            video.m3u8
            #EXT-X-I-FRAME-STREAM-INF:RESOLUTION=3840x2160,URI="iframes.m3u8"
        """.trimIndent()
        assertEquals(1080, ByseProtocol.masterQuality(manifest))
    }
}
