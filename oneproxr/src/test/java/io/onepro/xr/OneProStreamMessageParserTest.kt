package io.onepro.xr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneProStreamMessageParserTest {
    private val primaryHeader = byteArrayOf(0x28, 0x36, 0x00, 0x00, 0x00, 0x80.toByte())
    private val alternateHeader = byteArrayOf(0x27, 0x36, 0x00, 0x00, 0x00, 0x80.toByte())

    @Test
    fun streamFramerDecodesMappedImuSampleFromHeaderAndMarker() {
        val frame = buildFrame(
            gx = 1.5f,
            gy = -2.25f,
            gz = 3.75f,
            ax = -9.0f,
            ay = 4.0f,
            az = 7.5f
        )

        val framer = OneProStreamMessageParser.StreamFramer()
        val result = framer.append(frame)

        assertEquals(1, result.sensorSamples.size)
        assertEquals(1L, result.diagnosticsDelta.parsedMessageCount)
        assertEquals(0L, result.diagnosticsDelta.rejectedMessageCount)
        assertEquals(0L, result.diagnosticsDelta.droppedBytes)

        val sample = result.sensorSamples.first()
        assertEquals(1.5f, sample.gx, 0.0001f)
        assertEquals(-2.25f, sample.gy, 0.0001f)
        assertEquals(3.75f, sample.gz, 0.0001f)
        assertEquals(-9.0f, sample.ax, 0.0001f)
        assertEquals(4.0f, sample.ay, 0.0001f)
        assertEquals(7.5f, sample.az, 0.0001f)
    }

    @Test
    fun streamFramerDecodesFramesWithTrailingPacketBytes() {
        val packet1 = buildFrame(
            gx = 0.5f,
            gy = 0.6f,
            gz = 0.7f,
            ax = 0.8f,
            ay = 0.9f,
            az = 1.0f,
            includeTrailingPacketBytes = true
        )
        val packet2 = buildFrame(
            gx = -1.5f,
            gy = -1.6f,
            gz = -1.7f,
            ax = -1.8f,
            ay = -1.9f,
            az = -2.0f,
            header = alternateHeader,
            includeTrailingPacketBytes = true
        )

        val framer = OneProStreamMessageParser.StreamFramer()
        val result = framer.append(packet1 + packet2)

        assertEquals(2, result.sensorSamples.size)
        assertEquals(2L, result.diagnosticsDelta.parsedMessageCount)
        assertEquals(0L, result.diagnosticsDelta.rejectedMessageCount)
        assertEquals(0L, result.diagnosticsDelta.droppedBytes)
    }

    @Test
    fun streamFramerResynchronizesAfterGarbageAndChunking() {
        val frame = buildFrame(
            gx = 0.5f,
            gy = 0.6f,
            gz = 0.7f,
            ax = 0.8f,
            ay = 0.9f,
            az = 1.0f
        )

        val framer = OneProStreamMessageParser.StreamFramer()
        val first = framer.append(byteArrayOf(0x10, 0x11, 0x12) + frame.copyOfRange(0, 25))
        val second = framer.append(frame.copyOfRange(25, frame.size))

        assertEquals(3L, first.diagnosticsDelta.droppedBytes)
        assertTrue(first.sensorSamples.isEmpty())

        assertEquals(1, second.sensorSamples.size)
        assertEquals(1L, second.diagnosticsDelta.parsedMessageCount)
    }

    @Test
    fun streamFramerDecodesFrameWithAlternateHeader() {
        val frame = buildFrame(
            gx = 2.5f,
            gy = -1.0f,
            gz = 0.75f,
            ax = -4.0f,
            ay = 3.25f,
            az = 8.5f,
            header = alternateHeader
        )

        val framer = OneProStreamMessageParser.StreamFramer()
        val result = framer.append(frame)

        assertEquals(1, result.sensorSamples.size)
        assertEquals(1L, result.diagnosticsDelta.parsedMessageCount)
        assertEquals(0L, result.diagnosticsDelta.rejectedMessageCount)
    }

    @Test
    fun streamFramerDecodesFrameWhenSensorMarkerOffsetShifts() {
        val frame = buildFrame(
            gx = -0.5f,
            gy = 1.25f,
            gz = 4.5f,
            ax = -6.0f,
            ay = 2.5f,
            az = 9.75f,
            extraSensorDataBytes = 41
        )

        val framer = OneProStreamMessageParser.StreamFramer()
        val result = framer.append(frame)

        assertEquals(1, result.sensorSamples.size)
        assertEquals(1L, result.diagnosticsDelta.parsedMessageCount)
        assertEquals(0L, result.diagnosticsDelta.rejectedMessageCount)
    }

    @Test
    fun streamFramerTracksRejectedFramesWhenMarkerIsMissing() {
        val invalidFrame = buildFrame(
            gx = 1.0f,
            gy = 2.0f,
            gz = 3.0f,
            ax = 4.0f,
            ay = 5.0f,
            az = 6.0f,
            includeSensorMarker = false
        )

        val framer = OneProStreamMessageParser.StreamFramer()
        val result = framer.append(invalidFrame)

        assertTrue(result.sensorSamples.isEmpty())
        assertEquals(0L, result.diagnosticsDelta.parsedMessageCount)
        assertEquals(1L, result.diagnosticsDelta.missingSensorMarkerCount)
        assertEquals(1L, result.diagnosticsDelta.rejectedMessageCount)
    }

    private fun buildFrame(
        gx: Float,
        gy: Float,
        gz: Float,
        ax: Float,
        ay: Float,
        az: Float,
        header: ByteArray = primaryHeader,
        includeSensorMarker: Boolean = true,
        extraSensorDataBytes: Int = 20,
        includeTrailingPacketBytes: Boolean = false
    ): ByteArray {
        val sensorMarker = if (includeSensorMarker) {
            byteArrayOf(0x00, 0x40, 0x1F, 0x00, 0x00, 0x40)
        } else {
            byteArrayOf(0x60, 0x50, 0x40, 0x30, 0x20, 0x10)
        }

        val imuValues = floatArrayOf(gx, gy, gz, az, ay, ax)
        val imuData = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                imuValues.forEach { putFloat(it) }
            }
            .array()

        val session = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(777L).array()
        val timestamp = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(123L).array()
        val dataHeader = ByteArray(12)
        val extraSensorData = ByteArray(extraSensorDataBytes.coerceAtLeast(0))
        val minimalFrame = header + session + timestamp + dataHeader + imuData + extraSensorData + sensorMarker
        if (!includeTrailingPacketBytes) {
            return minimalFrame
        }

        return minimalFrame + ByteArray(50)
    }
}
