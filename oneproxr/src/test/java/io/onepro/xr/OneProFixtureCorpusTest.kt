package io.onepro.xr

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneProFixtureCorpusTest {
    @Test
    fun fixtureMetadataMatchesPayloadChecksumAndSize() {
        val payload = loadResourceBytes("packets/onepro_stream_capture_v1.bin")
        val metadata = loadResourceText("packets/onepro_stream_capture_v1.meta.json")

        val schemaVersion = metadata.readString("schema_version")
        val durationSeconds = metadata.readDouble("duration_seconds")
        val totalBytes = metadata.readLong("total_bytes")
        val sha256 = metadata.readString("sha256")
        val parserContractVersion = metadata.readString("parser_contract_version")

        assertEquals("onepro_stream_fixture.v1", schemaVersion)
        assertTrue(durationSeconds > 0.0)
        assertEquals(payload.size.toLong(), totalBytes)
        assertTrue(payload.size <= 5 * 1024 * 1024)
        assertEquals(sha256Hex(payload), sha256)
        assertTrue(parserContractVersion.isNotBlank())
    }

    @Test
    fun fixtureCorpusContainsDecodableImuAndMagnetometerReports() {
        val payload = loadResourceBytes("packets/onepro_stream_capture_v1.bin")
        val framer = OneProReportMessageParser.StreamFramer()

        val firstHalf = framer.append(payload.copyOfRange(0, payload.size / 2))
        val secondHalf = framer.append(payload.copyOfRange(payload.size / 2, payload.size))
        val reports = firstHalf.reports + secondHalf.reports

        assertTrue(reports.isNotEmpty())
        assertTrue(reports.count { it.reportType == OneProReportType.IMU } > 0)
        assertTrue(reports.count { it.reportType == OneProReportType.MAGNETOMETER } > 0)

        val parsedCount = firstHalf.diagnosticsDelta.parsedMessageCount + secondHalf.diagnosticsDelta.parsedMessageCount
        val imuCount = firstHalf.diagnosticsDelta.imuReportCount + secondHalf.diagnosticsDelta.imuReportCount
        val magCount = firstHalf.diagnosticsDelta.magnetometerReportCount + secondHalf.diagnosticsDelta.magnetometerReportCount
        val rejectedCount = firstHalf.diagnosticsDelta.rejectedMessageCount + secondHalf.diagnosticsDelta.rejectedMessageCount

        assertEquals(reports.size.toLong(), parsedCount)
        assertTrue(imuCount > 0L)
        assertTrue(magCount > 0L)
        assertTrue(rejectedCount >= 0L)
    }

    private fun loadResourceBytes(path: String): ByteArray {
        return javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes() }
    }

    private fun loadResourceText(path: String): String {
        return javaClass.classLoader!!.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
    }

    private fun String.readString(key: String): String {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        val match = regex.find(this) ?: error("missing string key: $key")
        return match.groupValues[1]
    }

    private fun String.readLong(key: String): Long {
        val regex = Regex("\"$key\"\\s*:\\s*([0-9]+)")
        val match = regex.find(this) ?: error("missing long key: $key")
        return match.groupValues[1].toLong()
    }

    private fun String.readDouble(key: String): Double {
        val regex = Regex("\"$key\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
        val match = regex.find(this) ?: error("missing double key: $key")
        return match.groupValues[1].toDouble()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        digest.forEach { value ->
            val v = value.toInt() and 0xFF
            out.append((v ushr 4).toString(16))
            out.append((v and 0x0F).toString(16))
        }
        return out.toString()
    }
}
