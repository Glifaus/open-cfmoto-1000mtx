package dev.zanderp.opencfmoto.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AaMicrophoneTest {
    @Test
    fun buildsRawMicPacketWithoutMessageTypePrefix() {
        val packet = AaMicrophone.buildMediaData(
            samples = shortArrayOf(0x1234, -2),
            count = 2,
            timestampMs = 0x0102030405060708L,
        )

        assertEquals(Channel.ID_MIC, packet.channel)
        assertEquals(0x0b.toByte(), packet.flags)
        assertEquals(-1, packet.type)
        assertEquals(2, packet.dataOffset)
        assertEquals(14, packet.size)
        assertArrayEquals(
            byteArrayOf(
                0x07, 0x0b,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x34, 0x12, 0xfe.toByte(), 0xff.toByte(),
            ),
            packet.data,
        )
    }
}
