package com.ultracinetv

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.util.concurrent.TimeUnit

internal class AbyssHttp : Closeable {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()

    fun fetch(source: AbyssProxy.Source, part: Long, start: Long, end: Long): ByteArray {
        val expected = (end - start + 1).toInt()
        require(expected in 1..262144)
        var failure: Exception? = null
        for (base in source.urls) {
            try {
                val request = Request.Builder().url(base + if (part == 0L) "" else part.toString())
                    .header("Range", "bytes=$start-$end").header("Referer", source.referer)
                    .header("Accept-Encoding", "identity").build()
                return client.newCall(request).execute().use { response ->
                    require(response.code == 206) { "Abyss upstream did not honor byte range (${response.code})" }
                    val contentRange = response.header("Content-Range").orEmpty()
                    require(contentRange.startsWith("bytes $start-$end/")) { "Unexpected Abyss content range" }
                    val body = response.body ?: error("Empty Abyss response")
                    require(body.contentLength() == -1L || body.contentLength() == expected.toLong())
                    val bytes = ByteArray(expected)
                    body.byteStream().use { input ->
                        var offset = 0
                        while (offset < expected) {
                            val count = input.read(bytes, offset, expected - offset)
                            require(count > 0) { "Truncated Abyss response" }; offset += count
                        }
                    }
                    bytes
                }
            } catch (e: Exception) { failure = e }
        }
        throw failure ?: IllegalStateException("No Abyss origins")
    }

    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}
