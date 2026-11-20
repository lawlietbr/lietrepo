package com.ultracinetv

import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

internal class AbyssProxy(private val fetch: (Source, Long, Long, Long) -> ByteArray) : Closeable {
    data class Source(val urls: List<String>, val size: Long, val partSize: Long, val seed: String, val referer: String)
    private class Entry(val source: Source) {
        @Volatile var touched = System.currentTimeMillis()
        @Volatile var initial: ByteArray? = null
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "UltraCine-Abyss-stream").apply { isDaemon = true } })
    private val listener = Executors.newSingleThreadExecutor { task ->
        Thread(task, "UltraCine-Abyss-listener").apply { isDaemon = true }
    }

    init {
        listener.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                clients += client
                try { workers.execute { serve(client) } }
                catch (_: RejectedExecutionException) { clients -= client; client.close() }
            }
        }
    }

    @Synchronized fun register(source: Source): String {
        require(source.size > 0 && source.partSize >= AbyssProtocol.INITIAL_SIZE && source.urls.isNotEmpty())
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        entries.entries.removeAll { it.value.touched < cutoff }
        if (entries.size >= 32) entries.entries.minByOrNull { it.value.touched }?.let { entries.remove(it.key) }
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(source)
        return "http://127.0.0.1:${socket.localPort}/$token/video.mp4"
    }

    private fun initial(entry: Entry): ByteArray {
        entry.initial?.let { return it }
        return synchronized(entry) {
            entry.initial ?: AbyssProtocol.initialBlock(fetch(entry.source, 0, 0,
                minOf(entry.source.size, AbyssProtocol.INITIAL_SIZE.toLong()) - 1), entry.source.seed)
                .also { entry.initial = it }
        }
    }

    private fun write(entry: Entry, range: LongRange, output: OutputStream) {
        var start = range.first
        if (start < AbyssProtocol.INITIAL_SIZE) {
            val first = initial(entry)
            val end = minOf(range.last + 1, first.size.toLong()).toInt()
            output.write(first, start.toInt(), end - start.toInt())
            start = end.toLong()
        }
        for (span in AbyssProtocol.spans(start..range.last, entry.source.partSize, 262144)) {
            entry.touched = System.currentTimeMillis()
            val bytes = fetch(entry.source, span.part, span.start, span.end)
            require(bytes.size.toLong() == span.end - span.start + 1) { "Incomplete Abyss range" }
            output.write(bytes)
        }
    }

    private fun header(output: OutputStream, status: String, length: Long, extra: String = "") {
        output.write(("HTTP/1.1 $status\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\n" +
            "Content-Type: video/mp4\r\nConnection: close\r\n$extra\r\n").toByteArray(Charsets.US_ASCII))
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                client.soTimeout = 15000
                val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val request = input.readLine()?.split(' ') ?: return
                val output = client.getOutputStream().buffered()
                if (request.size != 3 || request[0] !in listOf("GET", "HEAD")) {
                    header(output, "405 Method Not Allowed", 0); output.flush(); return
                }
                var rangeHeader: String? = null
                var read = 0
                while (true) {
                    val line = input.readLine() ?: return
                    if (line.isEmpty()) break
                    read += line.length
                    require(read <= 8192)
                    if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(':').trim()
                }
                val token = Regex("^/([a-f0-9-]+)/video\\.mp4$").matchEntire(request[1])?.groupValues?.get(1)
                val entry = token?.let { entries[it] }
                if (entry == null) { header(output, "404 Not Found", 0); output.flush(); return }
                entry.touched = System.currentTimeMillis()
                val range = try { AbyssProtocol.range(rangeHeader, entry.source.size) } catch (_: IllegalArgumentException) {
                    header(output, "416 Range Not Satisfiable", 0, "Content-Range: bytes */${entry.source.size}\r\n")
                    output.flush(); return
                }
                if (request[0] == "GET" && range.first < AbyssProtocol.INITIAL_SIZE) initial(entry)
                val partial = rangeHeader != null
                header(output, if (partial) "206 Partial Content" else "200 OK", range.last - range.first + 1,
                    if (partial) "Content-Range: bytes ${range.first}-${range.last}/${entry.source.size}\r\n" else "")
                output.flush()
                if (request[0] == "GET") write(entry, range, output)
                output.flush()
            }
        } catch (_: Exception) {
            // The player closes old range requests when seeking or changing sources.
        } finally { clients -= client; runCatching { client.close() } }
    }

    override fun close() {
        socket.close()
        clients.forEach { runCatching { it.close() } }
        clients.clear(); workers.shutdownNow(); listener.shutdownNow(); entries.clear()
    }
}
