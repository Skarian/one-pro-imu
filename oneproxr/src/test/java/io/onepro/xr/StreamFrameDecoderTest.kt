package io.onepro.xr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StreamFrameDecoderTest {
    @Test
    fun decodesObservedFrameFromDevice() {
        val payload = hexToBytes("28360000008018307d00000000008a41ac8a4f00000000000000000000000400")

        val decoded = StreamFrameDecoder.decode(index = 0, payload = payload)

        assertEquals(32, decoded.byteCount)
        assertEquals(40, decoded.candidateReportId)
        assertEquals(54, decoded.candidateVersion)
        assertEquals(0, decoded.candidateTemperatureRaw)
        assertEquals(25.0, decoded.candidateTemperatureCelsius)
        assertEquals(4, decoded.candidateTailWord30)
        assertNotNull(decoded.candidateTimestampRawLe)
    }

    @Test
    fun handlesShortFrameWithoutThrowing() {
        val payload = hexToBytes("0102030405")

        val decoded = StreamFrameDecoder.decode(index = 0, payload = payload)

        assertEquals(5, decoded.byteCount)
        assertEquals(1, decoded.candidateReportId)
        assertEquals(2, decoded.candidateVersion)
        assertNull(decoded.candidateTimestampRawLe)
        assertNull(decoded.candidateTailWord30)
    }

    @Test
    fun decodesSigned24CandidateGyroValues() {
        val payload = ByteArray(32)
        payload[18] = 0x01
        payload[19] = 0x02
        payload[20] = 0x03
        payload[21] = 0xFF.toByte()
        payload[22] = 0xFF.toByte()
        payload[23] = 0x7F
        payload[24] = 0x00
        payload[25] = 0x00
        payload[26] = 0x80.toByte()

        val decoded = StreamFrameDecoder.decode(index = 0, payload = payload)

        assertEquals(197121, decoded.candidateGyroPackedX)
        assertEquals(8388607, decoded.candidateGyroPackedY)
        assertEquals(-8388608, decoded.candidateGyroPackedZ)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { index ->
            val offset = index * 2
            val byteHex = hex.substring(offset, offset + 2)
            byteHex.toInt(16).toByte()
        }
    }
}
