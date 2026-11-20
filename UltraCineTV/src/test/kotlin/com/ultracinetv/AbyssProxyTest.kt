package com.ultracinetv

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class AbyssProxyTest {
    private val bytes = ByteArray(140000) { (it % 251).toByte() }.also { "ftypmp42".toByteArray().copyInto(it, 4) }
    private fun proxy() = AbyssProxy { _, part, start, end ->
        bytes.copyOfRange((part * 65536 + start).toInt(), (part * 65536 + end + 1).toInt())
    }
    private fun source() = AbyssProxy.Source(listOf("https://example.invalid/file"), bytes.size.toLong(), 65536, "file", "https://example.invalid/")
    private fun request(url: String, range: String?, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000; c.requestMethod = method
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    @Test fun headDoesNotFetchUpstream() {
        AbyssProxy { _, _, _, _ -> error("HEAD must not fetch") }.use { p ->
            val c = request(p.register(source()), null, "HEAD")
            assertEquals(200, c.responseCode)
            assertEquals(bytes.size.toLong(), c.getHeaderFieldLong("Content-Length", -1))
            c.disconnect()
        }
    }

    @Test fun servesExactCrossPartRangeAndRejectsInvalidRanges() {
        proxy().use { p ->
            val url = p.register(source())
            val c = request(url, "bytes=65534-65539")
            assertEquals(206, c.responseCode)
            assertEquals("bytes 65534-65539/140000", c.getHeaderField("Content-Range"))
            assertArrayEquals(bytes.copyOfRange(65534, 65540), c.inputStream.readBytes()); c.disconnect()
            val bad = request(url, "bytes=140000-")
            assertEquals(416, bad.responseCode); bad.disconnect()
        }
    }

    @Test fun isolatesRegistrationsAndServesConcurrentSeeks() {
        proxy().use { p ->
            val first = p.register(source()); val second = p.register(source())
            assertNotEquals(first, second)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val tasks = listOf(0, 100000).map { offset -> pool.submit<ByteArray> {
                    val c = request(first, "bytes=$offset-${offset + 31}")
                    try { c.inputStream.readBytes() } finally { c.disconnect() }
                } }
                assertArrayEquals(bytes.copyOfRange(0, 32), tasks[0].get())
                assertArrayEquals(bytes.copyOfRange(100000, 100032), tasks[1].get())
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun rejectsUnknownSourceWithoutFetchingAndClosesListener() {
        val p = AbyssProxy { _, _, _, _ -> error("Unknown source must not fetch") }
        val url = p.register(source())
        val c = request(url.substringBeforeLast('/') + "/not-a-video", null)
        assertEquals(404, c.responseCode); c.disconnect()
        p.close()
        assertThrows(java.io.IOException::class.java) { request(url, "bytes=0-1").inputStream.read() }
    }
}
