package com.myhome.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmWavTest {
    @Test
    fun writesPcmMonoWavHeaderAndPayload() {
        val wav = PcmWav.encode(byteArrayOf(1, 2, 3, 4), 16_000)

        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(40, littleEndianInt(wav, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(1, littleEndianShort(wav, 20)) // PCM format
        assertEquals(1, littleEndianShort(wav, 22)) // mono
        assertEquals(16_000, littleEndianInt(wav, 24))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(4, littleEndianInt(wav, 40))
        assertTrue(wav.copyOfRange(44, 48).contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun appendWallpaperProgressCountsOnlyTheNewPhotos() {
        val status = WallpaperStatus(running = true, photos = 18, count = 10, base = 12, append = true)

        assertEquals(6, status.freshPhotos)
        assertEquals(0.6f, status.progress, 0.001f)
        assertEquals("6/10", status.badge)
    }

    @Test
    fun voiceResponseCombinesTranscriptAndActionFeedback() {
        val result = VoiceResult("open Netflix", listOf(VoiceAction(true, "Netflix opened")))

        assertEquals("Heard: \"open Netflix\"\n✓ Netflix opened", result.userMessage())
    }

    @Test
    fun serverAddressAcceptsAndNormalizesAnOrigin() {
        assertEquals("http://192.168.1.9:6969", ServerAddress.normalize(" http://192.168.1.9:6969/ "))
        assertEquals("https://remote.home", ServerAddress.normalize("HTTPS://remote.home"))
        assertEquals("http://lenovo:6969", ServerAddress.normalize("http://lenovo:6969"))
    }

    @Test
    fun serverAddressRejectsCredentialsAndPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerAddress.normalize("http://user:password@192.168.1.9:6969")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerAddress.normalize("http://192.168.1.9:6969/admin")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerAddress.normalize("http://example.com:6969")
        }
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.normalize("file:///etc/passwd") }
    }

    @Test
    fun dockArtworkMustStayOnTheRemoteServer() {
        assertEquals(
            "http://192.168.1.9:6969/icons/netflix.svg",
            resolveDockIconUrl("http://192.168.1.9:6969", "/icons/netflix.svg"),
        )
        assertEquals(null, resolveDockIconUrl("http://192.168.1.9:6969", "https://example.com/icon.svg"))
        assertEquals(null, resolveDockIconUrl("http://192.168.1.9:6969", "/../admin"))
    }

    @Test
    fun wavEncoderRejectsInvalidPcmShape() {
        assertThrows(IllegalArgumentException::class.java) { PcmWav.encode(byteArrayOf(1), 16_000) }
        assertThrows(IllegalArgumentException::class.java) { PcmWav.encode(byteArrayOf(1, 2), 0) }
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
