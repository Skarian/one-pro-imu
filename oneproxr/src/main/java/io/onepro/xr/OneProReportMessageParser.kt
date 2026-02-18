package io.onepro.xr

internal object OneProReportMessageParser {
    private const val primaryMagic0 = 0x28
    private const val alternateMagic0 = 0x27
    private const val magic1 = 0x36
    private const val headerBytes = 6
    private const val expectedReportBodyBytes = 128
    private const val maxPendingBytes = 131_072

    data class ParseDiagnosticsDelta(
        val droppedBytes: Long = 0,
        val parsedMessageCount: Long = 0,
        val invalidReportLengthCount: Long = 0,
        val decodeErrorCount: Long = 0,
        val unknownReportTypeCount: Long = 0,
        val imuReportCount: Long = 0,
        val magnetometerReportCount: Long = 0
    ) {
        val rejectedMessageCount: Long
            get() = invalidReportLengthCount + decodeErrorCount + unknownReportTypeCount
    }

    data class AppendResult(
        val reports: List<OneProReportMessage>,
        val diagnosticsDelta: ParseDiagnosticsDelta
    )

    class StreamFramer {
        private var pending = ByteArray(0)

        fun append(chunk: ByteArray): AppendResult {
            if (chunk.isEmpty()) {
                return AppendResult(emptyList(), ParseDiagnosticsDelta())
            }

            pending += chunk
            val reports = mutableListOf<OneProReportMessage>()
            var droppedBytes = 0L
            var parsedMessageCount = 0L
            var invalidReportLengthCount = 0L
            var decodeErrorCount = 0L
            var unknownReportTypeCount = 0L
            var imuReportCount = 0L
            var magnetometerReportCount = 0L

            while (pending.size >= headerBytes) {
                val headerIndex = pending.findHeaderIndex()
                if (headerIndex < 0) {
                    val keep = 1.coerceAtMost(pending.size)
                    droppedBytes += (pending.size - keep).toLong()
                    pending = pending.copyOfRange(pending.size - keep, pending.size)
                    break
                }

                if (headerIndex > 0) {
                    droppedBytes += headerIndex.toLong()
                    pending = pending.copyOfRange(headerIndex, pending.size)
                    if (pending.size < headerBytes) {
                        break
                    }
                }

                val bodyLength = decodeBodyLengthBe(pending)
                if (bodyLength != expectedReportBodyBytes) {
                    invalidReportLengthCount += 1
                    droppedBytes += 1
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }

                val messageBytes = headerBytes + bodyLength
                if (pending.size < messageBytes) {
                    break
                }

                val body = pending.copyOfRange(headerBytes, messageBytes)
                when (val decode = decodeReportBody(body)) {
                    is DecodeResult.Success -> {
                        reports += decode.report
                        parsedMessageCount += 1
                        when (decode.report.reportType) {
                            OneProReportType.IMU -> imuReportCount += 1
                            OneProReportType.MAGNETOMETER -> magnetometerReportCount += 1
                        }
                    }

                    DecodeResult.UnknownReportType -> {
                        unknownReportTypeCount += 1
                    }

                    DecodeResult.DecodeError -> {
                        decodeErrorCount += 1
                    }
                }

                pending = pending.copyOfRange(messageBytes, pending.size)

                if (pending.size > maxPendingBytes) {
                    val keep = maxPendingBytes.coerceAtLeast(headerBytes - 1)
                    val drop = pending.size - keep
                    droppedBytes += drop.toLong()
                    pending = pending.copyOfRange(drop, pending.size)
                }
            }

            if (pending.size > maxPendingBytes) {
                val keep = maxPendingBytes.coerceAtLeast(headerBytes - 1)
                val drop = pending.size - keep
                droppedBytes += drop.toLong()
                pending = pending.copyOfRange(drop, pending.size)
            }

            return AppendResult(
                reports = reports,
                diagnosticsDelta = ParseDiagnosticsDelta(
                    droppedBytes = droppedBytes,
                    parsedMessageCount = parsedMessageCount,
                    invalidReportLengthCount = invalidReportLengthCount,
                    decodeErrorCount = decodeErrorCount,
                    unknownReportTypeCount = unknownReportTypeCount,
                    imuReportCount = imuReportCount,
                    magnetometerReportCount = magnetometerReportCount
                )
            )
        }
    }

    private sealed interface DecodeResult {
        data class Success(val report: OneProReportMessage) : DecodeResult
        data object UnknownReportType : DecodeResult
        data object DecodeError : DecodeResult
    }

    private fun decodeReportBody(body: ByteArray): DecodeResult {
        if (body.size != expectedReportBodyBytes) {
            return DecodeResult.DecodeError
        }

        return try {
            val reportTypeRaw = u32le(body, 0x18).toUInt()
            val reportType = OneProReportType.fromWireValue(reportTypeRaw)
                ?: return DecodeResult.UnknownReportType

            val report = OneProReportMessage(
                deviceId = u64le(body, 0x0),
                hmdTimeNanosDevice = u64le(body, 0x8),
                reportType = reportType,
                gx = f32le(body, 0x1c),
                gy = f32le(body, 0x20),
                gz = f32le(body, 0x24),
                ax = f32le(body, 0x28),
                ay = f32le(body, 0x2c),
                az = f32le(body, 0x30),
                mx = f32le(body, 0x34),
                my = f32le(body, 0x38),
                mz = f32le(body, 0x3c),
                temperatureCelsius = f32le(body, 0x40),
                imuId = body[0x44].toInt() and 0xFF,
                frameId = OneProFrameId(
                    byte0 = body[0x45].toInt() and 0xFF,
                    byte1 = body[0x46].toInt() and 0xFF,
                    byte2 = body[0x47].toInt() and 0xFF
                )
            )
            DecodeResult.Success(report)
        } catch (_: Throwable) {
            DecodeResult.DecodeError
        }
    }

    private fun decodeBodyLengthBe(message: ByteArray): Int {
        val b0 = message[2].toInt() and 0xFF
        val b1 = message[3].toInt() and 0xFF
        val b2 = message[4].toInt() and 0xFF
        val b3 = message[5].toInt() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun ByteArray.findHeaderIndex(): Int {
        if (size < 2) {
            return -1
        }
        val lastStart = size - 2
        for (index in 0..lastStart) {
            val b0 = this[index].toInt() and 0xFF
            val b1 = this[index + 1].toInt() and 0xFF
            if ((b0 == primaryMagic0 || b0 == alternateMagic0) && b1 == magic1) {
                return index
            }
        }
        return -1
    }

    private fun u32le(bytes: ByteArray, offset: Int): Long {
        val b0 = bytes[offset].toLong() and 0xFF
        val b1 = bytes[offset + 1].toLong() and 0xFF
        val b2 = bytes[offset + 2].toLong() and 0xFF
        val b3 = bytes[offset + 3].toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun u64le(bytes: ByteArray, offset: Int): ULong {
        var value = 0UL
        for (index in 0..7) {
            val byteValue = bytes[offset + index].toULong() and 0xFFUL
            value = value or (byteValue shl (index * 8))
        }
        return value
    }

    private fun f32le(bytes: ByteArray, offset: Int): Float {
        return Float.fromBits(u32le(bytes, offset).toInt())
    }
}
