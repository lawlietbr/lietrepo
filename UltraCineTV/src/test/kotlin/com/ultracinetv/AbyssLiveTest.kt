package com.ultracinetv

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class AbyssLiveTest {
    @Test fun decodesVideoAfterSeekAcrossRemotePartBoundary() {
        assumeTrue(System.getenv("ULTRA_LIVE") == "1")
        val input = File(requireNotNull(System.getenv("ULTRA_ABYSS_MEDIA")))
        val data = ObjectMapper().readTree(input)["mp4"]["sources"][0]
        val path = data["path"].asText()
        val source = AbyssProxy.Source(listOf(data["url"].asText().trimEnd('/') + "/" + path),
            data["size"].asLong(), data["partSize"].asLong(), path.substringAfterLast('/'), "https://player.abyssplayer.com/")
        AbyssHttp().use { upstream ->
            AbyssProxy(upstream::fetch).use { proxy ->
                val url = proxy.register(source)
                val process = ProcessBuilder("ffmpeg", "-v", "error", "-rw_timeout", "15000000", "-ss", "6500",
                    "-i", url, "-t", "2", "-map", "0:v:0", "-an", "-progress", "pipe:1", "-nostats", "-f", "null", "-")
                    .redirectErrorStream(true).start()
                val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
                if (!process.waitFor(75, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Live playback timed out") }
                val report = output.get(5, TimeUnit.SECONDS)
                assertEquals(report, 0, process.exitValue())
                assertTrue(report, report.contains("out_time_us=2000000"))
                val frames = Regex("frame=(\\d+)").findAll(report).last().groupValues[1].toInt()
                assertTrue(report, frames >= 40)
                println("LIVE_ABYSS_PASS frames=$frames seekSeconds=6500")
            }
        }
    }
}
