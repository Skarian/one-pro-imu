package io.onepro.xr

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object OneProStreamMessageParser {
    private val primaryHeader = byteArrayOf(0x28, 0x36, 0x00, 0x00, 0x00, 0x80.toByte())
    private val alternateHeader = byteArrayOf(0x27, 0x36, 0x00, 0x00, 0x00, 0x80.toByte())
    private val headers = arrayOf(primaryHeader, alternateHeader)
    private val sensorMarker = byteArrayOf(0x00, 0x40, 0x1F, 0x00, 0x00, 0x40)

    private const val sensorOffsetFromHeader = 28
    private const val nominalFrameBytes = 134
    private const val minSensorBytes = 24
    private const val maxPendingBytes = 131_072
    private val minHeaderBytes = headers.minOf { it.size }
    private val maxHeaderBytes = headers.maxOf { it.size }
    private val minimumFrameBytes = minHeaderBytes + sensorOffsetFromHeader + minSensorBytes + sensorMarker.size

    data class ParseDiagnosticsDelta(
        val droppedBytes: Long = 0,
        val parsedMessageCount: Long = 0,
        val tooShortMessageCount: Long = 0,
        val missingSensorMarkerCount: Long = 0,
        val invalidSensorSliceCount: Long = 0,
        val floatDecodeFailureCount: Long = 0
    ) {
        val rejectedMessageCount: Long
            get() {
                return tooShortMessageCount +
                    missingSensorMarkerCount +
                    invalidSensorSliceCount +
                    floatDecodeFailureCount
            }
    }

    data class AppendResult(
        val sensorSamples: List<OneProSensorSample>,
        val diagnosticsDelta: ParseDiagnosticsDelta
    )

    class StreamFramer {
        private var pending = ByteArray(0)

        fun append(chunk: ByteArray): AppendResult {
            if (chunk.isEmpty()) {
                return AppendResult(emptyList(), ParseDiagnosticsDelta())
            }

            pending += chunk
            val sensorSamples = mutableListOf<OneProSensorSample>()
            var droppedBytes = 0L
            var parsedMessageCount = 0L
            var tooShortMessageCount = 0L
            var missingSensorMarkerCount = 0L
            var invalidSensorSliceCount = 0L
            var floatDecodeFailureCount = 0L

            while (pending.size >= minHeaderBytes) {
                val firstHeaderMatch = pending.findHeaderMatch()
                if (firstHeaderMatch == null) {
                    val keep = (maxHeaderBytes - 1).coerceAtMost(pending.size)
                    droppedBytes += (pending.size - keep).toLong()
                    pending = if (keep == 0) {
                        ByteArray(0)
                    } else {
                        pending.copyOfRange(pending.size - keep, pending.size)
                    }
                    break
                }

                if (firstHeaderMatch.index > 0) {
                    droppedBytes += firstHeaderMatch.index.toLong()
                    pending = pending.copyOfRange(firstHeaderMatch.index, pending.size)
                }

                val activeHeaderMatch = pending.findHeaderMatch()
                if (activeHeaderMatch == null || activeHeaderMatch.index != 0) {
                    droppedBytes += 1
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }

                val nextHeaderIndex = pending.findHeaderMatch(startIndex = activeHeaderMatch.header.size)?.index ?: -1
                val message = if (nextHeaderIndex > 0) {
                    pending.copyOfRange(0, nextHeaderIndex)
                } else {
                    pending
                }

                when (val outcome = decodeMessage(message, activeHeaderMatch.header)) {
                    is DecodeOutcome.Success -> {
                        parsedMessageCount += 1
                        sensorSamples += outcome.sample
                        val consumeCount = when {
                            nextHeaderIndex > 0 -> nextHeaderIndex
                            pending.size >= nominalFrameBytes -> nominalFrameBytes
                            else -> outcome.consumedByteCount.coerceIn(1, pending.size)
                        }
                        pending = pending.copyOfRange(consumeCount, pending.size)
                    }

                    DecodeOutcome.TooShort -> {
                        tooShortMessageCount += 1
                        if (nextHeaderIndex > 0) {
                            droppedBytes += nextHeaderIndex.toLong()
                            pending = pending.copyOfRange(nextHeaderIndex, pending.size)
                            continue
                        }
                        break
                    }

                    DecodeOutcome.MissingSensorMarker -> {
                        missingSensorMarkerCount += 1
                        if (nextHeaderIndex > 0) {
                            droppedBytes += nextHeaderIndex.toLong()
                            pending = pending.copyOfRange(nextHeaderIndex, pending.size)
                        } else {
                            break
                        }
                    }

                    DecodeOutcome.InvalidSensorSlice -> {
                        invalidSensorSliceCount += 1
                        if (nextHeaderIndex > 0) {
                            droppedBytes += nextHeaderIndex.toLong()
                            pending = pending.copyOfRange(nextHeaderIndex, pending.size)
                        } else {
                            droppedBytes += 1
                            pending = pending.copyOfRange(1, pending.size)
                        }
                    }

                    DecodeOutcome.FloatDecodeFailure -> {
                        floatDecodeFailureCount += 1
                        if (nextHeaderIndex > 0) {
                            droppedBytes += nextHeaderIndex.toLong()
                            pending = pending.copyOfRange(nextHeaderIndex, pending.size)
                        } else {
                            droppedBytes += 1
                            pending = pending.copyOfRange(1, pending.size)
                        }
                    }
                }

                if (pending.size > maxPendingBytes) {
                    val keep = maxPendingBytes.coerceAtLeast(maxHeaderBytes - 1)
                    val drop = pending.size - keep
                    droppedBytes += drop.toLong()
                    pending = pending.copyOfRange(drop, pending.size)
                }
            }

            if (pending.size > maxPendingBytes) {
                val keep = maxPendingBytes.coerceAtLeast(maxHeaderBytes - 1)
                val drop = pending.size - keep
                droppedBytes += drop.toLong()
                pending = pending.copyOfRange(drop, pending.size)
            }

            return AppendResult(
                sensorSamples = sensorSamples,
                diagnosticsDelta = ParseDiagnosticsDelta(
                    droppedBytes = droppedBytes,
                    parsedMessageCount = parsedMessageCount,
                    tooShortMessageCount = tooShortMessageCount,
                    missingSensorMarkerCount = missingSensorMarkerCount,
                    invalidSensorSliceCount = invalidSensorSliceCount,
                    floatDecodeFailureCount = floatDecodeFailureCount
                )
            )
        }
    }

    private sealed interface DecodeOutcome {
        data class Success(
            val sample: OneProSensorSample,
            val consumedByteCount: Int
        ) : DecodeOutcome
        data object TooShort : DecodeOutcome
        data object MissingSensorMarker : DecodeOutcome
        data object InvalidSensorSlice : DecodeOutcome
        data object FloatDecodeFailure : DecodeOutcome
    }

    private data class HeaderMatch(
        val index: Int,
        val header: ByteArray
    )

    private fun decodeMessage(message: ByteArray, header: ByteArray): DecodeOutcome {
        val sensorStartOffset = header.size + sensorOffsetFromHeader
        val markerSearchStart = sensorStartOffset + minSensorBytes
        val minimumBytesForHeader = header.size + sensorOffsetFromHeader + minSensorBytes + sensorMarker.size
        if (message.size < minimumBytesForHeader || message.size < minimumFrameBytes) {
            return DecodeOutcome.TooShort
        }

        val sensorMarkerIndex = message.indexOf(sensorMarker, startIndex = markerSearchStart)
        if (sensorMarkerIndex < 0) {
            return DecodeOutcome.MissingSensorMarker
        }
        val sensorSliceLength = sensorMarkerIndex - sensorStartOffset
        if (sensorSliceLength < minSensorBytes) {
            return DecodeOutcome.InvalidSensorSlice
        }

        val values = try {
            ByteBuffer.wrap(message, sensorStartOffset, minSensorBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .let { buffer ->
                    FloatArray(6) { buffer.float }
                }
        } catch (_: Throwable) {
            return DecodeOutcome.FloatDecodeFailure
        }

        return DecodeOutcome.Success(
            OneProSensorSample(
                gx = values[0],
                gy = values[1],
                gz = values[2],
                ax = values[5],
                ay = values[4],
                az = values[3]
            ),
            consumedByteCount = sensorMarkerIndex + sensorMarker.size
        )
    }

    private fun ByteArray.findHeaderMatch(startIndex: Int = 0): HeaderMatch? {
        var selected: HeaderMatch? = null
        headers.forEach { header ->
            val index = indexOf(header, startIndex = startIndex)
            if (index >= 0) {
                val current = selected
                if (current == null || index < current.index) {
                    selected = HeaderMatch(index, header)
                }
            }
        }
        return selected
    }

    private fun ByteArray.indexOf(pattern: ByteArray, startIndex: Int = 0): Int {
        if (size < pattern.size) {
            return -1
        }
        val boundedStart = startIndex.coerceAtLeast(0)
        val lastStart = size - pattern.size
        if (boundedStart > lastStart) {
            return -1
        }
        for (start in boundedStart..lastStart) {
            var matches = true
            for (offset in pattern.indices) {
                if (this[start + offset] != pattern[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                return start
            }
        }
        return -1
    }
}
