package com.FilmeFlix

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.toByteString

internal object AbyssProtocol {
    const val INITIAL_SIZE = 65536
    data class Span(val part: Long, val start: Long, val end: Long, val global: Long)

    fun legacyToken(path: String, size: Long): String {
        val digits = size.toString().map { it.digitToInt().toByte() }.toByteArray()
        val key = MessageDigest.getInstance("MD5").digest(digits).joinToString("") { "%02x".format(it) }
            .toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
        val first = cipher.doFinal(path.toByteArray(Charsets.UTF_8)).toByteString().base64().trimEnd('=')
        return first.toByteArray(Charsets.US_ASCII).toByteString().base64().trimEnd('=')
    }

    fun range(header: String?, size: Long): LongRange {
        require(size > 0)
        if (header == null) return 0L until size
        val match = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE).matchEntire(header.trim())
            ?: throw IllegalArgumentException("Invalid byte range")
        val (from, to) = match.destructured
        if (from.isEmpty()) {
            val suffix = to.toLongOrNull() ?: throw IllegalArgumentException("Invalid suffix")
            require(suffix > 0)
            return maxOf(0, size - suffix)..size - 1
        }
        val start = from.toLongOrNull() ?: throw IllegalArgumentException("Invalid offset")
        val end = if (to.isEmpty()) size - 1 else minOf(to.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid offset"), size - 1)
        require(start <= end && start < size)
        return start..end
    }

    fun spans(range: LongRange, partSize: Long, chunkSize: Int): List<Span> {
        require(partSize > 0 && chunkSize > 0)
        val result = mutableListOf<Span>()
        var at = range.first
        while (at <= range.last) {
            val part = at / partSize
            val end = minOf(range.last, at + chunkSize - 1, (part + 1) * partSize - 1)
            result += Span(part, at % partSize, end % partSize, at)
            at = end + 1
        }
        return result
    }

    fun decrypt(bytes: ByteArray, seed: String): ByteArray {
        val key = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
        return cipher.doFinal(bytes)
    }

    fun initialBlock(bytes: ByteArray, seed: String): ByteArray {
        require(bytes.size >= 12)
        if (String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp") return bytes
        val count = minOf(INITIAL_SIZE, bytes.size)
        val decoded = decrypt(bytes.copyOfRange(0, count), seed)
        require(String(decoded, 4, 4, Charsets.US_ASCII) == "ftyp") { "Abyss MP4 header could not be decoded" }
        return decoded + bytes.copyOfRange(count, bytes.size)
    }
}
