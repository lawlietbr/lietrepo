package com.ultracinetv

import java.net.URI

internal object ByseProtocol {
    fun masterQuality(manifest: String): Int = manifest.lineSequence()
        .map { it.trim() }.filter { it.startsWith("#EXT-X-STREAM-INF:") }
        .mapNotNull { Regex("RESOLUTION=\\d+x(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull() ?: 0

    fun code(url: String): String {
        val parts = URI(url).path.split('/').filter { it.isNotBlank() }
        require(parts.size >= 2)
        return parts[1]
    }

    fun keyIndexes(version: Int?, size: Int): List<Int> {
        if (version == null) return (0 until size).toList()
        require(version in 1..20 && size == 30) { "Unsupported Byse key version" }
        return listOf(version - 1, 30 - version)
    }

    private fun round(e: IntArray) {
        e[0] += e[1]; e[3] = Integer.rotateLeft(e[3] xor e[0], 16)
        e[2] += e[3]; e[1] = Integer.rotateLeft(e[1] xor e[2], 12)
        e[0] += e[1]; e[3] = Integer.rotateLeft(e[3] xor e[0], 8)
        e[2] += e[3]; e[1] = Integer.rotateLeft(e[1] xor e[2], 7)
    }

    private fun hash(text: String): IntArray {
        val e = intArrayOf(1779033703, 3144134277L.toInt(), 1013904242, 2773480762L.toInt())
        text.forEach { e[0] += it.code and 255; e[0] = Integer.rotateLeft(e[0], 7); round(e) }
        repeat(8) { round(e) }
        val memory = IntArray(512) { round(e); e[0] xor e[2] }
        repeat(2) {
            for (i in memory.indices) {
                var c = Integer.rotateLeft(memory[i] + memory[memory[i] and 511], 13)
                c = c xor (memory[(i + 1) and 511] * 2654435761L.toInt())
                memory[i] = c; e[0] = e[0] xor c; round(e)
            }
        }
        return IntArray(8) { i ->
            round(e)
            var s = e[0]
            repeat(64) { j ->
                val value = memory[i * 64 + j]
                s = Integer.rotateLeft(s + value, 5) xor (value * 2246822519L.toInt())
            }
            s xor e[2]
        }
    }

    fun verify(nonce: String, solution: Int, difficulty: Int): Boolean {
        var bits = 0
        for (word in hash("$nonce:$solution")) {
            bits += Integer.numberOfLeadingZeros(word)
            if (word != 0) break
        }
        return bits >= difficulty
    }
}
