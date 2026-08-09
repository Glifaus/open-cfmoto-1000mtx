package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HuTimeSyncTest {
    @Test
    fun ackPayload_preservesHeaderAndWrites29CharStamp() {
        val req = ByteArray(45)
        ByteBuffer.wrap(req).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(-2)
            .putInt(37426)
            .putInt(7)
            .putInt(0)
        "2026-01-01 00:00:00.000000000".toByteArray(Charsets.US_ASCII).copyInto(req, 16)

        val ack = HuTimeSync.ackPayload(req)
        assertEquals(45, ack.size)
        val hdr = ByteBuffer.wrap(ack).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-2, hdr.int)
        assertEquals(37426, hdr.int)
        assertEquals(7, hdr.int)
        assertEquals(0, hdr.int)
        val stamp = String(ack, 16, 29, Charsets.US_ASCII)
        assertEquals(29, stamp.length)
        assertTrue(stamp.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{9}""")))
    }
}
