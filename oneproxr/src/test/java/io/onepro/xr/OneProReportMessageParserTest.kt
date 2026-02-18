package io.onepro.xr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneProReportMessageParserTest {
    @Test
    fun streamFramerDecodesAllRequiredFieldsFromPrimaryHeader() {
        val packet = buildPacket(
            magic0 = 0x28,
            deviceId = 0x0102030405060708UL,
            hmdTimeNanosDevice = 0x1112131415161718UL,
            reportType = OneProReportType.IMU.wireValue,
            gx = 1.25f,
            gy = -2.5f,
            gz = 3.75f,
            ax = -4.5f,
            ay = 5.25f,
            az = 6.5f,
            mx = -7.75f,
            my = 8.875f,
            mz = 9.5f,
            temperatureCelsius = 31.25f,
            imuId = 19,
            frameId = intArrayOf(0xAA, 0xBB, 0xCC)
        )

        val framer = OneProReportMessageParser.StreamFramer()
        val result = framer.append(packet)

        assertEquals(1, result.reports.size)
        assertEquals(1L, result.diagnosticsDelta.parsedMessageCount)
        assertEquals(1L, result.diagnosticsDelta.imuReportCount)
        assertEquals(0L, result.diagnosticsDelta.rejectedMessageCount)

        val report = result.reports.first()
        assertEquals(0x0102030405060708UL, report.deviceId)
        assertEquals(0x1112131415161718UL, report.hmdTimeNanosDevice)
        assertEquals(OneProReportType.IMU, report.reportType)
        assertEquals(1.25f, report.gx, 0.0001f)
        assertEquals(-2.5f, report.gy, 0.0001f)
        assertEquals(3.75f, report.gz, 0.0001f)
        assertEquals(-4.5f, report.ax, 0.0001f)
        assertEquals(5.25f, report.ay, 0.0001f)
        assertEquals(6.5f, report.az, 0.0001f)
        assertEquals(-7.75f, report.mx, 0.0001f)
        assertEquals(8.875f, report.my, 0.0001f)
        assertEquals(9.5f, report.mz, 0.0001f)
        assertEquals(31.25f, report.temperatureCelsius, 0.0001f)
        assertEquals(19, report.imuId)
        assertEquals(0xAA, report.frameId.byte0)
        assertEquals(0xBB, report.frameId.byte1)
        assertEquals(0xCC, report.frameId.byte2)
    }

    @Test
    fun streamFramerDecodesMagnetometerReportFromAlternateHeader() {
        val packet = buildPacket(
            magic0 = 0x27,
            hmdTimeNanosDevice = 9000UL,
            reportType = OneProReportType.MAGNETOMETER.wireValue,
            mx = 0.1f,
            my = 0.2f,
            mz = 0.3f
        )

        val framer = OneProReportMessageParser.StreamFramer()
        val result = framer.append(packet)

        assertEquals(1, result.reports.size)
        assertEquals(1L, result.diagnosticsDelta.parsedMessageCount)
        assertEquals(1L, result.diagnosticsDelta.magnetometerReportCount)
        assertEquals(OneProReportType.MAGNETOMETER, result.reports.first().reportType)
    }

    @Test
    fun streamFramerResynchronizesAfterGarbageAndChunking() {
        val packet = buildPacket(
            reportType = OneProReportType.IMU.wireValue,
            gx = 0.5f,
            gy = 0.6f,
            gz = 0.7f,
            ax = 0.8f,
            ay = 0.9f,
            az = 1.0f
        )

        val framer = OneProReportMessageParser.StreamFramer()
        val first = framer.append(byteArrayOf(0x44, 0x45, 0x46) + packet.copyOfRange(0, 32))
        val second = framer.append(packet.copyOfRange(32, packet.size))

        assertTrue(first.reports.isEmpty())
        assertEquals(3L, first.diagnosticsDelta.droppedBytes)
        assertEquals(1, second.reports.size)
        assertEquals(1L, second.diagnosticsDelta.parsedMessageCount)
    }

    @Test
    fun streamFramerCountsInvalidReportLength() {
        val invalidPacket = buildPacketWithLength(length = 120, body = ByteArray(120))

        val framer = OneProReportMessageParser.StreamFramer()
        val result = framer.append(invalidPacket)

        assertTrue(result.reports.isEmpty())
        assertEquals(1L, result.diagnosticsDelta.invalidReportLengthCount)
        assertEquals(1L, result.diagnosticsDelta.rejectedMessageCount)
    }

    @Test
    fun streamFramerCountsUnknownReportType() {
        val unknownPacket = buildPacket(
            reportType = 0x00000099U
        )

        val framer = OneProReportMessageParser.StreamFramer()
        val result = framer.append(unknownPacket)

        assertTrue(result.reports.isEmpty())
        assertEquals(1L, result.diagnosticsDelta.unknownReportTypeCount)
        assertEquals(1L, result.diagnosticsDelta.rejectedMessageCount)
    }

    @Test
    fun streamFramerTracksRoutingCountersAcrossMixedReports() {
        val imuPacket = buildPacket(
            reportType = OneProReportType.IMU.wireValue,
            hmdTimeNanosDevice = 100UL
        )
        val magPacket = buildPacket(
            reportType = OneProReportType.MAGNETOMETER.wireValue,
            hmdTimeNanosDevice = 101UL
        )
        val unknownPacket = buildPacket(
            reportType = 0x0000AAAAU,
            hmdTimeNanosDevice = 102UL
        )

        val framer = OneProReportMessageParser.StreamFramer()
        val result = framer.append(imuPacket + magPacket + unknownPacket)

        assertEquals(2, result.reports.size)
        assertEquals(2L, result.diagnosticsDelta.parsedMessageCount)
        assertEquals(1L, result.diagnosticsDelta.imuReportCount)
        assertEquals(1L, result.diagnosticsDelta.magnetometerReportCount)
        assertEquals(1L, result.diagnosticsDelta.unknownReportTypeCount)
        assertEquals(1L, result.diagnosticsDelta.rejectedMessageCount)
    }

    private fun buildPacket(
        magic0: Int = 0x28,
        deviceId: ULong = 1UL,
        hmdTimeNanosDevice: ULong = 2UL,
        reportType: UInt,
        gx: Float = 0.0f,
        gy: Float = 0.0f,
        gz: Float = 0.0f,
        ax: Float = 0.0f,
        ay: Float = 0.0f,
        az: Float = 0.0f,
        mx: Float = 0.0f,
        my: Float = 0.0f,
        mz: Float = 0.0f,
        temperatureCelsius: Float = 25.0f,
        imuId: Int = 7,
        frameId: IntArray = intArrayOf(1, 2, 3)
    ): ByteArray {
        val body = ByteArray(128)
        writeU64Le(body, 0x0, deviceId)
        writeU64Le(body, 0x8, hmdTimeNanosDevice)
        writeU32Le(body, 0x18, reportType.toLong())
        writeF32Le(body, 0x1c, gx)
        writeF32Le(body, 0x20, gy)
        writeF32Le(body, 0x24, gz)
        writeF32Le(body, 0x28, ax)
        writeF32Le(body, 0x2c, ay)
        writeF32Le(body, 0x30, az)
        writeF32Le(body, 0x34, mx)
        writeF32Le(body, 0x38, my)
        writeF32Le(body, 0x3c, mz)
        writeF32Le(body, 0x40, temperatureCelsius)
        body[0x44] = (imuId and 0xFF).toByte()
        body[0x45] = (frameId[0] and 0xFF).toByte()
        body[0x46] = (frameId[1] and 0xFF).toByte()
        body[0x47] = (frameId[2] and 0xFF).toByte()

        return buildPacketWithLength(length = body.size, body = body, magic0 = magic0)
    }

    private fun buildPacketWithLength(length: Int, body: ByteArray, magic0: Int = 0x28): ByteArray {
        val header = byteArrayOf(
            (magic0 and 0xFF).toByte(),
            0x36,
            ((length ushr 24) and 0xFF).toByte(),
            ((length ushr 16) and 0xFF).toByte(),
            ((length ushr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte()
        )
        return header + body
    }

    private fun writeU32Le(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeU64Le(target: ByteArray, offset: Int, value: ULong) {
        for (index in 0..7) {
            target[offset + index] = ((value shr (index * 8)) and 0xFFUL).toByte()
        }
    }

    private fun writeF32Le(target: ByteArray, offset: Int, value: Float) {
        writeU32Le(target, offset, value.toRawBits().toLong() and 0xFFFFFFFFL)
    }
}
