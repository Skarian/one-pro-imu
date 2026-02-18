package io.onepro.xr

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class OneProXrClient(
    private val context: Context,
    private val endpoint: OneProXrEndpoint = OneProXrEndpoint()
) {
    private data class NetworkCandidate(
        val network: Network,
        val interfaceName: String,
        val addresses: List<String>
    )

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun describeRouting(): RoutingSnapshot = withContext(Dispatchers.IO) {
        val interfaces = listInterfaces()
        val networkCandidates = networkCandidatesForHost(endpoint.host)
        val addressCandidates = addressCandidatesForHost(endpoint.host)
        RoutingSnapshot(
            interfaces = interfaces,
            networkCandidates = networkCandidates.map {
                NetworkCandidateInfo(
                    networkHandle = it.network.networkHandle,
                    interfaceName = it.interfaceName,
                    addresses = it.addresses
                )
            },
            addressCandidates = addressCandidates
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun connectControlChannel(
        connectTimeoutMs: Int = 1500,
        readTimeoutMs: Int = 400
    ): ControlChannelResult = withContext(Dispatchers.IO) {
        val candidates = networkCandidatesForHost(endpoint.host)
        val selected = preferredNetwork(endpoint.host, candidates)
        if (selected == null) {
            return@withContext ControlChannelResult(
                success = false,
                networkHandle = null,
                interfaceName = null,
                localSocket = null,
                remoteSocket = null,
                connectMs = 0,
                readSummary = "not-run",
                error = "No matching Android Network candidate for host ${endpoint.host}"
            )
        }

        val startNanos = System.nanoTime()
        return@withContext try {
            selected.network.socketFactory.createSocket().use { socket ->
                socket.soTimeout = readTimeoutMs
                socket.connect(java.net.InetSocketAddress(endpoint.host, endpoint.controlPort), connectTimeoutMs)
                val connectMs = (System.nanoTime() - startNanos) / 1_000_000
                val localSocket = "${socket.localAddress.hostAddress}:${socket.localPort}"
                val remoteSocket = "${socket.inetAddress.hostAddress}:${socket.port}"
                val readSummary = readSummary(socket.getInputStream(), 32)
                ControlChannelResult(
                    success = true,
                    networkHandle = selected.network.networkHandle,
                    interfaceName = selected.interfaceName,
                    localSocket = localSocket,
                    remoteSocket = remoteSocket,
                    connectMs = connectMs,
                    readSummary = readSummary,
                    error = null
                )
            }
        } catch (t: Throwable) {
            val connectMs = (System.nanoTime() - startNanos) / 1_000_000
            ControlChannelResult(
                success = false,
                networkHandle = selected.network.networkHandle,
                interfaceName = selected.interfaceName,
                localSocket = null,
                remoteSocket = "${endpoint.host}:${endpoint.controlPort}",
                connectMs = connectMs,
                readSummary = "not-run",
                error = "${t.javaClass.simpleName}:${t.message ?: "no-message"}"
            )
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun readSensorFrames(
        frameCount: Int = 4,
        frameSizeBytes: Int = 32,
        connectTimeoutMs: Int = 1500,
        readTimeoutMs: Int = 700,
        maxReadBytes: Int = 256,
        syncMarker: ByteArray = byteArrayOf(0x28, 0x36)
    ): StreamReadResult = withContext(Dispatchers.IO) {
        if (frameSizeBytes <= 0) {
            return@withContext StreamReadResult(
                success = false,
                networkHandle = null,
                interfaceName = null,
                localSocket = null,
                remoteSocket = null,
                connectMs = 0,
                frames = emptyList(),
                rateEstimate = null,
                readStatus = "not-run",
                error = "frameSizeBytes must be > 0"
            )
        }
        val candidates = networkCandidatesForHost(endpoint.host)
        val selected = preferredNetwork(endpoint.host, candidates)
        if (selected == null) {
            return@withContext StreamReadResult(
                success = false,
                networkHandle = null,
                interfaceName = null,
                localSocket = null,
                remoteSocket = null,
                connectMs = 0,
                frames = emptyList(),
                rateEstimate = null,
                readStatus = "not-run",
                error = "No matching Android Network candidate for host ${endpoint.host}"
            )
        }

        val startNanos = System.nanoTime()
        return@withContext try {
            selected.network.socketFactory.createSocket().use { socket ->
                socket.soTimeout = readTimeoutMs
                socket.connect(java.net.InetSocketAddress(endpoint.host, endpoint.streamPort), connectTimeoutMs)
                val connectMs = (System.nanoTime() - startNanos) / 1_000_000
                val localSocket = "${socket.localAddress.hostAddress}:${socket.localPort}"
                val remoteSocket = "${socket.inetAddress.hostAddress}:${socket.port}"
                val frames = mutableListOf<DecodedSensorFrame>()
                val captureStartNanos = System.nanoTime()
                var pending = ByteArray(0)
                var readStatus = "completed"
                while (frames.size < frameCount) {
                    val payload = try {
                        readFrame(socket.getInputStream(), maxReadBytes)
                    } catch (_: SocketTimeoutException) {
                        readStatus = "timeout"
                        null
                    }
                    if (payload == null) {
                        if (readStatus == "completed") {
                            readStatus = "eof"
                        }
                        break
                    }
                    val readNanos = System.nanoTime()
                    pending += payload
                    while (pending.size >= frameSizeBytes && frames.size < frameCount) {
                        if (syncMarker.size >= 2) {
                            val syncIndex = findSyncIndex(pending, syncMarker)
                            if (syncIndex < 0) {
                                pending = trimPendingTail(pending, frameSizeBytes - 1)
                                break
                            }
                            if (syncIndex > 0) {
                                pending = pending.copyOfRange(syncIndex, pending.size)
                                if (pending.size < frameSizeBytes) {
                                    break
                                }
                            }
                        }
                        val framePayload = pending.copyOfRange(0, frameSizeBytes)
                        frames += StreamFrameDecoder.decode(
                            index = frames.size,
                            payload = framePayload,
                            captureMonotonicNanos = readNanos
                        )
                        pending = pending.copyOfRange(frameSizeBytes, pending.size)
                    }
                }
                val captureEndNanos = System.nanoTime()
                val success = frames.isNotEmpty()
                val rateEstimate = StreamRateEstimator.estimate(
                    frames = frames,
                    captureStartNanos = captureStartNanos,
                    captureEndNanos = captureEndNanos
                )
                StreamReadResult(
                    success = success,
                    networkHandle = selected.network.networkHandle,
                    interfaceName = selected.interfaceName,
                    localSocket = localSocket,
                    remoteSocket = remoteSocket,
                    connectMs = connectMs,
                    frames = frames,
                    rateEstimate = rateEstimate,
                    readStatus = readStatus,
                    error = if (success) null else "No frames read ($readStatus)"
                )
            }
        } catch (t: Throwable) {
            val connectMs = (System.nanoTime() - startNanos) / 1_000_000
            StreamReadResult(
                success = false,
                networkHandle = selected.network.networkHandle,
                interfaceName = selected.interfaceName,
                localSocket = null,
                remoteSocket = "${endpoint.host}:${endpoint.streamPort}",
                connectMs = connectMs,
                frames = emptyList(),
                rateEstimate = null,
                readStatus = "connect-failed",
                error = "${t.javaClass.simpleName}:${t.message ?: "no-message"}"
            )
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun captureStreamBytes(
        durationSeconds: Int = 45,
        maxCaptureBytes: Int = 5 * 1024 * 1024,
        connectTimeoutMs: Int = 1500,
        readTimeoutMs: Int = 700,
        readChunkBytes: Int = 4096
    ): StreamCaptureResult = withContext(Dispatchers.IO) {
        if (durationSeconds <= 0) {
            return@withContext StreamCaptureResult(
                success = false,
                networkHandle = null,
                interfaceName = null,
                localSocket = null,
                remoteSocket = null,
                connectMs = 0,
                durationMs = 0,
                totalBytes = 0,
                payload = ByteArray(0),
                readStatus = "not-run",
                error = "durationSeconds must be > 0"
            )
        }
        if (maxCaptureBytes <= 0) {
            return@withContext StreamCaptureResult(
                success = false,
                networkHandle = null,
                interfaceName = null,
                localSocket = null,
                remoteSocket = null,
                connectMs = 0,
                durationMs = 0,
                totalBytes = 0,
                payload = ByteArray(0),
                readStatus = "not-run",
                error = "maxCaptureBytes must be > 0"
            )
        }
        if (readChunkBytes <= 0) {
            return@withContext StreamCaptureResult(
                success = false,
                networkHandle = null,
                interfaceName = null,
                localSocket = null,
                remoteSocket = null,
                connectMs = 0,
                durationMs = 0,
                totalBytes = 0,
                payload = ByteArray(0),
                readStatus = "not-run",
                error = "readChunkBytes must be > 0"
            )
        }

        val candidates = networkCandidatesForHost(endpoint.host)
        val selected = preferredNetwork(endpoint.host, candidates)
        if (selected == null) {
            return@withContext StreamCaptureResult(
                success = false,
                networkHandle = null,
                interfaceName = null,
                localSocket = null,
                remoteSocket = null,
                connectMs = 0,
                durationMs = 0,
                totalBytes = 0,
                payload = ByteArray(0),
                readStatus = "not-run",
                error = "No matching Android Network candidate for host ${endpoint.host}"
            )
        }

        val startNanos = System.nanoTime()
        return@withContext try {
            selected.network.socketFactory.createSocket().use { socket ->
                socket.soTimeout = readTimeoutMs
                socket.connect(java.net.InetSocketAddress(endpoint.host, endpoint.streamPort), connectTimeoutMs)
                val connectMs = (System.nanoTime() - startNanos) / 1_000_000
                val localSocket = "${socket.localAddress.hostAddress}:${socket.localPort}"
                val remoteSocket = "${socket.inetAddress.hostAddress}:${socket.port}"
                val captureStartNanos = System.nanoTime()
                val captureDeadlineNanos = captureStartNanos + durationSeconds.toLong() * 1_000_000_000L
                val buffer = ByteArray(readChunkBytes)
                val output = ByteArrayOutputStream(maxCaptureBytes.coerceAtMost(262_144))
                var readStatus = "completed"

                while (currentCoroutineContext().isActive) {
                    if (System.nanoTime() >= captureDeadlineNanos) {
                        readStatus = "duration-reached"
                        break
                    }
                    if (output.size() >= maxCaptureBytes) {
                        readStatus = "max-bytes-reached"
                        break
                    }

                    val readCount = try {
                        socket.getInputStream().read(buffer)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }

                    if (readCount <= 0) {
                        readStatus = "eof"
                        break
                    }

                    val remaining = maxCaptureBytes - output.size()
                    if (remaining <= 0) {
                        readStatus = "max-bytes-reached"
                        break
                    }
                    val bytesToWrite = readCount.coerceAtMost(remaining)
                    output.write(buffer, 0, bytesToWrite)
                    if (bytesToWrite < readCount) {
                        readStatus = "max-bytes-reached"
                        break
                    }
                }

                val durationMs = (System.nanoTime() - captureStartNanos) / 1_000_000
                val payload = output.toByteArray()
                val success = payload.isNotEmpty()
                StreamCaptureResult(
                    success = success,
                    networkHandle = selected.network.networkHandle,
                    interfaceName = selected.interfaceName,
                    localSocket = localSocket,
                    remoteSocket = remoteSocket,
                    connectMs = connectMs,
                    durationMs = durationMs,
                    totalBytes = payload.size,
                    payload = payload,
                    readStatus = readStatus,
                    error = if (success) null else "No bytes captured ($readStatus)"
                )
            }
        } catch (t: Throwable) {
            val connectMs = (System.nanoTime() - startNanos) / 1_000_000
            StreamCaptureResult(
                success = false,
                networkHandle = selected.network.networkHandle,
                interfaceName = selected.interfaceName,
                localSocket = null,
                remoteSocket = "${endpoint.host}:${endpoint.streamPort}",
                connectMs = connectMs,
                durationMs = 0,
                totalBytes = 0,
                payload = ByteArray(0),
                readStatus = "connect-failed",
                error = "${t.javaClass.simpleName}:${t.message ?: "no-message"}"
            )
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun streamHeadTracking(
        config: HeadTrackingStreamConfig = HeadTrackingStreamConfig()
    ): Flow<HeadTrackingStreamEvent> = flow {
        if (config.readChunkBytes <= 0) {
            emit(HeadTrackingStreamEvent.StreamError("readChunkBytes must be > 0"))
            return@flow
        }
        if (config.diagnosticsIntervalSamples <= 0) {
            emit(HeadTrackingStreamEvent.StreamError("diagnosticsIntervalSamples must be > 0"))
            return@flow
        }
        if (config.calibrationSampleTarget <= 0) {
            emit(HeadTrackingStreamEvent.StreamError("calibrationSampleTarget must be > 0"))
            return@flow
        }

        val candidates = networkCandidatesForHost(endpoint.host)
        val selected = preferredNetwork(endpoint.host, candidates)
        if (selected == null) {
            emit(
                HeadTrackingStreamEvent.StreamError(
                    "No matching Android Network candidate for host ${endpoint.host}"
                )
            )
            return@flow
        }

        val controlChannel = config.controlChannel ?: HeadTrackingControlChannel()
        val diagnosticsTracker = HeadTrackingDiagnosticsTracker()
        val framer = OneProReportMessageParser.StreamFramer()
        val tracker = OneProHeadTracker(
            OneProHeadTrackerConfig(
                calibrationSampleTarget = config.calibrationSampleTarget,
                complementaryFilterAlpha = config.complementaryFilterAlpha,
                pitchScale = config.pitchScale,
                yawScale = config.yawScale,
                rollScale = config.rollScale
            )
        )

        var sampleIndex = 0L
        var stopReason = "completed"
        var calibrationProgressReported = -1
        var pendingAutoZeroView = config.autoZeroViewOnStart
        var trackingSamplesAfterCalibration = 0L

        try {
            selected.network.socketFactory.createSocket().use { socket ->
                socket.soTimeout = config.readTimeoutMs
                val connectStart = System.nanoTime()
                socket.connect(
                    java.net.InetSocketAddress(endpoint.host, endpoint.streamPort),
                    config.connectTimeoutMs
                )
                val connectMs = (System.nanoTime() - connectStart) / 1_000_000
                val localSocket = "${socket.localAddress.hostAddress}:${socket.localPort}"
                val remoteSocket = "${socket.inetAddress.hostAddress}:${socket.port}"

                emit(
                    HeadTrackingStreamEvent.Connected(
                        networkHandle = selected.network.networkHandle,
                        interfaceName = selected.interfaceName,
                        localSocket = localSocket,
                        remoteSocket = remoteSocket,
                        connectMs = connectMs
                    )
                )
                emit(
                    HeadTrackingStreamEvent.CalibrationProgress(
                        calibrationSampleCount = 0,
                        calibrationTarget = tracker.calibrationTarget,
                        progressPercent = 0.0f,
                        isComplete = false
                    )
                )

                val readBuffer = ByteArray(config.readChunkBytes)
                val input = socket.getInputStream()

                while (currentCoroutineContext().isActive) {
                    val readCount = try {
                        input.read(readBuffer)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (readCount <= 0) {
                        stopReason = "eof"
                        break
                    }

                    val appendResult = framer.append(readBuffer.copyOf(readCount))
                    diagnosticsTracker.recordParserDelta(appendResult.diagnosticsDelta)
                    if (appendResult.reports.isEmpty()) {
                        continue
                    }

                    appendResult.reports.forEach { report ->
                        emit(HeadTrackingStreamEvent.ReportAvailable(report))

                        if (report.reportType != OneProReportType.IMU) {
                            return@forEach
                        }

                        val sensorSample = toSensorSample(report)

                        if (controlChannel.consumeRecalibrationRequest()) {
                            tracker.resetCalibration()
                            calibrationProgressReported = -1
                            trackingSamplesAfterCalibration = 0L
                            emit(
                                HeadTrackingStreamEvent.CalibrationProgress(
                                    calibrationSampleCount = 0,
                                    calibrationTarget = tracker.calibrationTarget,
                                    progressPercent = 0.0f,
                                    isComplete = false
                                )
                            )
                        }

                        if (!tracker.isCalibrated) {
                            val calibrationState = tracker.calibrateGyroscope(sensorSample)
                            if (shouldEmitCalibrationProgress(calibrationState, calibrationProgressReported)) {
                                calibrationProgressReported = calibrationState.sampleCount
                                emit(
                                    HeadTrackingStreamEvent.CalibrationProgress(
                                        calibrationSampleCount = calibrationState.sampleCount,
                                        calibrationTarget = calibrationState.target,
                                        progressPercent = calibrationState.progressPercent,
                                        isComplete = calibrationState.isCalibrated
                                    )
                                )
                            }
                            if (calibrationState.isCalibrated) {
                                pendingAutoZeroView = config.autoZeroViewOnStart
                                trackingSamplesAfterCalibration = 0L
                            }
                            return@forEach
                        }

                        if (controlChannel.consumeZeroViewRequest()) {
                            tracker.zeroView()
                        }

                        val update = tracker.update(
                            sensorSample = sensorSample,
                            deviceTimestampNanos = report.hmdTimeNanosDevice
                        ) ?: return@forEach

                        trackingSamplesAfterCalibration += 1
                        if (
                            pendingAutoZeroView &&
                            trackingSamplesAfterCalibration >= config.autoZeroViewAfterSamples.coerceAtLeast(1).toLong()
                        ) {
                            tracker.zeroView()
                            pendingAutoZeroView = false
                        }

                        sampleIndex += 1
                        val captureMonotonicNanos = System.nanoTime()
                        diagnosticsTracker.recordTrackingSample(captureMonotonicNanos)

                        emit(
                            HeadTrackingStreamEvent.TrackingSampleAvailable(
                                sample = HeadTrackingSample(
                                    sampleIndex = sampleIndex,
                                    captureMonotonicNanos = captureMonotonicNanos,
                                    deltaTimeSeconds = update.deltaTimeSeconds,
                                    absoluteOrientation = update.absoluteOrientation,
                                    relativeOrientation = update.relativeOrientation,
                                    calibrationSampleCount = tracker.calibrationCount,
                                    calibrationTarget = tracker.calibrationTarget,
                                    isCalibrated = tracker.isCalibrated
                                )
                            )
                        )

                        if (sampleIndex % config.diagnosticsIntervalSamples.toLong() == 0L) {
                            emit(
                                HeadTrackingStreamEvent.DiagnosticsAvailable(
                                    diagnostics = diagnosticsTracker.snapshot()
                                )
                            )
                        }
                    }
                }
            }

            emit(
                HeadTrackingStreamEvent.DiagnosticsAvailable(
                    diagnostics = diagnosticsTracker.snapshot()
                )
            )
            emit(HeadTrackingStreamEvent.StreamStopped(stopReason))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            emit(
                HeadTrackingStreamEvent.StreamError(
                    "${t.javaClass.simpleName}:${t.message ?: "no-message"}"
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private fun toSensorSample(report: OneProReportMessage): OneProImuVectorSample {
        return OneProImuVectorSample(
            gx = report.gx,
            gy = report.gy,
            gz = report.gz,
            ax = report.ax,
            ay = report.ay,
            az = report.az
        )
    }

    private fun shouldEmitCalibrationProgress(
        state: OneProCalibrationState,
        lastReportedCount: Int
    ): Boolean {
        if (state.sampleCount == lastReportedCount) {
            return false
        }
        if (state.isCalibrated) {
            return true
        }
        return state.sampleCount == 1 || state.sampleCount % 10 == 0
    }

    private fun readSummary(input: InputStream, maxBytes: Int): String {
        val payload = try {
            readFrame(input, maxBytes)
        } catch (_: SocketTimeoutException) {
            return "timeout"
        }
        if (payload == null) {
            return "eof"
        }
        return "bytes=${payload.size} hex=${payload.toHexString()}"
    }

    private fun readFrame(input: InputStream, maxFrameBytes: Int): ByteArray? {
        val buffer = ByteArray(maxFrameBytes)
        val read = input.read(buffer)
        if (read <= 0) {
            return null
        }
        return buffer.copyOf(read)
    }

    private fun findSyncIndex(source: ByteArray, marker: ByteArray): Int {
        if (source.size < marker.size) {
            return -1
        }
        val lastStart = source.size - marker.size
        for (start in 0..lastStart) {
            var matches = true
            for (offset in marker.indices) {
                if (source[start + offset] != marker[offset]) {
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

    private fun trimPendingTail(source: ByteArray, keepBytes: Int): ByteArray {
        if (keepBytes <= 0) {
            return ByteArray(0)
        }
        if (source.size <= keepBytes) {
            return source
        }
        return source.copyOfRange(source.size - keepBytes, source.size)
    }

    private fun listInterfaces(): List<InterfaceInfo> {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        return interfaces.sortedBy { it.name }.mapNotNull { iface ->
            val addresses = Collections.list(iface.inetAddresses).mapNotNull { it.hostAddress }
            if (addresses.isEmpty()) {
                null
            } else {
                InterfaceInfo(
                    name = iface.name,
                    isUp = iface.isUp,
                    addresses = addresses
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun networkCandidatesForHost(host: String): List<NetworkCandidate> {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return emptyList()
        val candidates = mutableListOf<NetworkCandidate>()
        connectivityManager.allNetworks.forEach { network ->
            val linkProperties = connectivityManager.getLinkProperties(network) ?: return@forEach
            val interfaceName = linkProperties.interfaceName ?: return@forEach
            val addresses = linkProperties.linkAddresses
                .mapNotNull { it.address.hostAddress }
                .filter { it.isNotEmpty() }
            if (host.startsWith("169.254.")) {
                if (addresses.any { it.startsWith("169.254.") }) {
                    candidates += NetworkCandidate(network, interfaceName, addresses)
                }
            } else {
                candidates += NetworkCandidate(network, interfaceName, addresses)
            }
        }
        return candidates.sortedBy { it.interfaceName }
    }

    private fun addressCandidatesForHost(host: String): List<AddressCandidateInfo> {
        if (!host.startsWith("169.254.")) {
            return emptyList()
        }
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        val candidates = mutableListOf<AddressCandidateInfo>()
        interfaces.forEach { iface ->
            Collections.list(iface.inetAddresses).forEach addressLoop@{ address ->
                val hostAddress = address.hostAddress ?: return@addressLoop
                if (hostAddress.startsWith("169.254.")) {
                    candidates += AddressCandidateInfo(
                        interfaceName = iface.name,
                        address = hostAddress
                    )
                }
            }
        }
        return candidates.sortedBy { "${it.interfaceName}-${it.address}" }
    }

    private fun preferredNetwork(
        host: String,
        candidates: List<NetworkCandidate>
    ): NetworkCandidate? {
        if (candidates.isEmpty()) {
            return null
        }
        val hostPrefix = prefix24(host)
        val samePrefix = candidates.firstOrNull { candidate ->
            candidate.addresses.any { address -> prefix24(address) == hostPrefix }
        }
        return samePrefix ?: candidates.first()
    }

    private fun prefix24(address: String): String? {
        val raw = address.substringBefore('%')
        val parts = raw.split(".")
        if (parts.size != 4) {
            return null
        }
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) {
            return null
        }
        return "${octets[0]}.${octets[1]}.${octets[2]}"
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
