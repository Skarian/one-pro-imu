package io.onepro.xr

import org.junit.Assert.assertEquals
import org.junit.Test

class OneProTrackerSampleMapperTest {
    @Test
    fun fromReportPreservesGyroAndUsesLegacyAccelAxisOrderForTracker() {
        val report = OneProReportMessage(
            deviceId = 1UL,
            hmdTimeNanosDevice = 2UL,
            reportType = OneProReportType.IMU,
            gx = 11.0f,
            gy = 12.0f,
            gz = 13.0f,
            ax = 21.0f,
            ay = 22.0f,
            az = 23.0f,
            mx = 31.0f,
            my = 32.0f,
            mz = 33.0f,
            temperatureCelsius = 30.0f,
            imuId = 7,
            frameId = OneProFrameId(byte0 = 1, byte1 = 2, byte2 = 3)
        )

        val sample = OneProTrackerSampleMapper.fromReport(report)

        assertEquals(11.0f, sample.gx, 0.0001f)
        assertEquals(12.0f, sample.gy, 0.0001f)
        assertEquals(13.0f, sample.gz, 0.0001f)
        assertEquals(23.0f, sample.ax, 0.0001f)
        assertEquals(22.0f, sample.ay, 0.0001f)
        assertEquals(21.0f, sample.az, 0.0001f)
    }
}
