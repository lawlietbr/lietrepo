package com.ultracinetv

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

class AbyssProtocolTest {
    @Test fun reproducesNumericKeyDerivationUsedByLegacyNativePlayer() {
        assertEquals("dVl2QlJhNGordXZ6MjE5Nk9ZRFlaalN3TGl3", AbyssProtocol.legacyToken("/mp4/42/2/100?v=test", 100))
    }
    @Test fun readsRangesWithoutTruncatingLongFiles() {
        assertEquals(10L..99L, AbyssProtocol.range("bytes=10-", 100))
        assertEquals(90L..99L, AbyssProtocol.range("bytes=-10", 100))
        assertEquals(0L..99L, AbyssProtocol.range(null, 100))
        assertEquals(90L..99L, AbyssProtocol.range("bytes=90-200", 100))
        assertEquals(5_000_000_000L..5_000_000_999L, AbyssProtocol.range("bytes=5000000000-", 5_000_001_000))
    }

    @Test fun rejectsUnsatisfiableAndMultipleRanges() {
        for (value in listOf("bytes=100-", "bytes=5-1", "bytes=-0", "bytes=0-1,3-4", "bytes=999999999999999999999-")) {
            assertThrows(IllegalArgumentException::class.java) { AbyssProtocol.range(value, 100) }
        }
    }

    @Test fun mapsRangeAcrossPublishedPartBoundary() {
        val spans = AbyssProtocol.spans(536870910L..536870915L, 536870912, 262144)
        assertEquals(2, spans.size)
        assertEquals(AbyssProtocol.Span(0, 536870910L, 536870911L, 536870910L), spans[0])
        assertEquals(AbyssProtocol.Span(1, 0L, 3L, 536870912L), spans[1])
    }

    @Test fun decryptsOnlyFirst64KiBWithPublishedKeyDerivation() {
        val seed = "test.200000.3"
        val plain = ByteArray(100000) { (it % 251).toByte() }
        "ftypmp42".toByteArray().copyInto(plain, 4)
        val key = MessageDigest.getInstance("MD5").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }.toByteArray()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
        val encrypted = cipher.doFinal(plain.copyOfRange(0, 65536)) + plain.copyOfRange(65536, plain.size)
        assertArrayEquals(plain, AbyssProtocol.initialBlock(encrypted, seed))
        assertArrayEquals(plain, AbyssProtocol.initialBlock(plain, seed))
    }
}
