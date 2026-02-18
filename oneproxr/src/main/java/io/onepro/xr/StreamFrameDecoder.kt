package io.onepro.xr

import kotlin.math.round

object StreamFrameDecoder {
    private const val TEMPERATURE_SCALE = 0.007548309
    private const val TEMPERATURE_OFFSET = 25.0

    fun decode(index: Int, payload: ByteArray, captureMonotonicNanos: Long? = null): DecodedSensorFrame {
        val reportId = u8(payload, 0)
        val version = u8(payload, 1)
        val temperatureRaw = u16le(payload, 2)
        val temperatureCelsius = temperatureRaw?.let {
            round((it * TEMPERATURE_SCALE + TEMPERATURE_OFFSET) * 1000.0) / 1000.0
        }
        val timestamp = u64le(payload, 4)?.toString()
        val word12 = u16le(payload, 12)
        val word14 = u32le(payload, 14)
        val gyroX = s24le(payload, 18)
        val gyroY = s24le(payload, 21)
        val gyroZ = s24le(payload, 24)
        val tailWord = u16le(payload, 30)

        return DecodedSensorFrame(
            index = index,
            byteCount = payload.size,
            captureMonotonicNanos = captureMonotonicNanos,
            rawHex = payload.toHexString(),
            candidateReportId = reportId,
            candidateVersion = version,
            candidateTemperatureRaw = temperatureRaw,
            candidateTemperatureCelsius = temperatureCelsius,
            candidateTimestampRawLe = timestamp,
            candidateWord12 = word12,
            candidateWord14 = word14,
            candidateGyroPackedX = gyroX,
            candidateGyroPackedY = gyroY,
            candidateGyroPackedZ = gyroZ,
            candidateTailWord30 = tailWord
        )
    }

    private fun u8(bytes: ByteArray, offset: Int): Int? {
        if (bytes.size < offset + 1) {
            return null
        }
        return bytes[offset].toInt() and 0xFF
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int? {
        if (bytes.size < offset + 2) {
            return null
        }
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        return b0 or (b1 shl 8)
    }

    private fun u32le(bytes: ByteArray, offset: Int): Long? {
        if (bytes.size < offset + 4) {
            return null
        }
        val b0 = bytes[offset].toLong() and 0xFF
        val b1 = bytes[offset + 1].toLong() and 0xFF
        val b2 = bytes[offset + 2].toLong() and 0xFF
        val b3 = bytes[offset + 3].toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun u64le(bytes: ByteArray, offset: Int): ULong? {
        if (bytes.size < offset + 8) {
            return null
        }
        var value = 0UL
        for (i in 0..7) {
            val byteValue = bytes[offset + i].toULong() and 0xFFUL
            value = value or (byteValue shl (i * 8))
        }
        return value
    }

    private fun s24le(bytes: ByteArray, offset: Int): Int? {
        if (bytes.size < offset + 3) {
            return null
        }
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val packed = b0 or (b1 shl 8) or (b2 shl 16)
        return if (packed and 0x800000 != 0) {
            packed or -0x1000000
        } else {
            packed
        }
    }

    private fun ByteArray.toHexString(): String {
        val builder = StringBuilder(size * 2)
        forEach { value ->
            val b = value.toInt() and 0xFF
            builder.append(((b ushr 4) and 0xF).toString(16))
            builder.append((b and 0xF).toString(16))
        }
        return builder.toString()
    }
}
