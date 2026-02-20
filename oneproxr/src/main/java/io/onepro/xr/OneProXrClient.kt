package io.onepro.xr

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.RequiresPermission
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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

    private data class RuntimeHandles(
        val streamJob: Job?,
        val controlSession: XrControlSession?,
        val controlEventJob: Job?
    )

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimeMutex = Mutex()
    private var streamJob: Job? = null
    private var activeConnectionInfo: XrConnectionInfo? = null
    private var activeControlChannel: HeadTrackingControlChannel? = null
    private var activeControlSession: XrControlSession? = null
    private var activeControlEventJob: Job? = null
    private var latestImuSample: XrImuSample? = null
    private var latestMagSample: XrMagSample? = null

    private val _sessionState = MutableStateFlow<XrSessionState>(XrSessionState.Idle)
    private val _biasState = MutableStateFlow<XrBiasState>(XrBiasState.Inactive)
    private val _sensorData = MutableStateFlow<XrSensorSnapshot?>(null)
    private val _poseData = MutableStateFlow<XrPoseSnapshot?>(null)
    private val _advancedDiagnostics = MutableStateFlow<HeadTrackingStreamDiagnostics?>(null)
    private val _advancedReports = MutableSharedFlow<OneProReportMessage>(extraBufferCapacity = 256)
    private val _advancedControlEvents = MutableSharedFlow<XrControlEvent>(extraBufferCapacity = 128)

    private val advancedApi = object : OneProXrAdvancedApi {
        override val diagnostics: StateFlow<HeadTrackingStreamDiagnostics?>
            get() = _advancedDiagnostics.asStateFlow()

        override val reports: SharedFlow<OneProReportMessage>
            get() = _advancedReports.asSharedFlow()

        override val controlEvents: SharedFlow<XrControlEvent>
            get() = _advancedControlEvents.asSharedFlow()
    }

    val sessionState: StateFlow<XrSessionState>
        get() = _sessionState.asStateFlow()

    /**
     * Emits bias activation transitions for tracker correction (`Inactive`, `LoadingConfig`, `Active`, `Error`).
     */
    val biasState: StateFlow<XrBiasState>
        get() = _biasState.asStateFlow()

    val sensorData: StateFlow<XrSensorSnapshot?>
        get() = _sensorData.asStateFlow()

    val poseData: StateFlow<XrPoseSnapshot?>
        get() = _poseData.asStateFlow()

    val advanced: OneProXrAdvancedApi
        get() = advancedApi

    private val startupTimeoutMs = 3500L

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
    suspend fun start(): XrConnectionInfo {
        val staleControlHandles = runtimeMutex.withLock {
            if (streamJob?.isActive == true) {
                return activeConnectionInfo ?: throw IllegalStateException("XR session is already starting")
            }
            activeConnectionInfo = null
            activeControlChannel = null
            val previousControlSession = activeControlSession
            activeControlSession = null
            val previousControlEventJob = activeControlEventJob
            activeControlEventJob = null
            latestImuSample = null
            latestMagSample = null
            _sensorData.value = null
            _poseData.value = null
            _advancedDiagnostics.value = null
            _sessionState.value = XrSessionState.Connecting
            _biasState.value = XrBiasState.LoadingConfig
            RuntimeHandles(
                streamJob = null,
                controlSession = previousControlSession,
                controlEventJob = previousControlEventJob
            )
        }
        staleControlHandles.controlEventJob?.cancel()
        staleControlHandles.controlSession?.close()

        val startupSignal = CompletableDeferred<XrConnectionInfo>()
        val controlChannel = HeadTrackingControlChannel()
        runtimeMutex.withLock {
            activeControlChannel = controlChannel
        }

        val job = clientScope.launch {
            try {
                streamHeadTracking(
                    config = HeadTrackingStreamConfig(
                        diagnosticsIntervalSamples = 240,
                        controlChannel = controlChannel
                    )
                ).collect { event ->
                    handleRuntimeEvent(event, startupSignal)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                handleRuntimeFailure(
                    code = XrSessionErrorCode.STREAM_ERROR,
                    message = "${t.javaClass.simpleName}:${t.message ?: "no-message"}",
                    startupSignal = startupSignal
                )
            }
        }

        runtimeMutex.withLock {
            streamJob = job
        }

        return try {
            withTimeout(startupTimeoutMs) {
                startupSignal.await()
            }
        } catch (_: TimeoutCancellationException) {
            handleRuntimeFailure(
                code = XrSessionErrorCode.STARTUP_TIMEOUT,
                message = "Timed out waiting for first valid report during startup",
                startupSignal = startupSignal
            )
            cancelRuntimeSession(awaitTermination = true, resetBiasState = true)
            throw IllegalStateException("Timed out waiting for first valid report during startup")
        }
    }

    suspend fun stop() {
        cancelRuntimeSession(awaitTermination = true, resetBiasState = true)
        _sessionState.value = XrSessionState.Stopped
    }

    fun isXrConnected(): Boolean {
        val state = _sessionState.value
        return state is XrSessionState.Calibrating || state is XrSessionState.Streaming
    }

    suspend fun getConnectionInfo(): XrConnectionInfo {
        return runtimeMutex.withLock {
            activeConnectionInfo ?: throw IllegalStateException("XR device is not connected")
        }
    }

    suspend fun zeroView() {
        val control = runtimeMutex.withLock { activeControlChannel }
            ?: throw IllegalStateException("XR session is not running")
        control.requestZeroView()
    }

    suspend fun recalibrate() {
        val control = runtimeMutex.withLock { activeControlChannel }
            ?: throw IllegalStateException("XR session is not running")
        control.requestRecalibration()
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun setSceneMode(mode: XrSceneMode) {
        sendSetNumericControlCommand(
            magic = XrControlMagic.SET_SCENE_MODE,
            value = mode.wireValue
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun setDisplayInputMode(mode: XrDisplayInputMode) {
        sendSetNumericControlCommand(
            magic = XrControlMagic.SET_DISPLAY_INPUT_MODE,
            value = mode.wireValue
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun setBrightness(level: Int) {
        if (level !in 0..9) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.INVALID_ARGUMENT,
                message = "Brightness level must be in range 0..9"
            )
        }
        sendSetNumericControlCommand(
            magic = XrControlMagic.SET_BRIGHTNESS,
            value = level
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun setDimmer(level: XrDimmerLevel) {
        sendSetNumericControlCommand(
            magic = XrControlMagic.SET_DIMMER,
            value = level.wireValue
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun getId(): String {
        val payload = sendGetPropertyControlCommand(XrControlMagic.GET_ID)
        return XrControlPropertyWire.parseStringPropertyResponse(payload)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun getSoftwareVersion(): String {
        val payload = sendGetPropertyControlCommand(XrControlMagic.GET_SOFTWARE_VERSION)
        return XrControlPropertyWire.parseStringPropertyResponse(payload)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun getDspVersion(): String {
        val payload = sendGetPropertyControlCommand(XrControlMagic.GET_DSP_VERSION)
        return XrControlPropertyWire.parseStringPropertyResponse(payload)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun getConfigRaw(): String {
        val payload = sendGetPropertyControlCommand(XrControlMagic.GET_CONFIG)
        return XrControlPropertyWire.parseStringPropertyResponse(payload)
    }

    /**
     * Loads and validates the typed device config from the control-channel JSON payload.
     *
     * Throws [XrControlProtocolException] for transport/protocol errors and
     * [XrDeviceConfigException] for parse/schema validation failures.
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    suspend fun getConfig(): XrDeviceConfig {
        return XrDeviceConfigParser.parse(getConfigRaw())
    }

    private suspend fun cancelRuntimeSession(
        awaitTermination: Boolean = false,
        resetBiasState: Boolean = false
    ) {
        val handles = runtimeMutex.withLock {
            val active = streamJob
            streamJob = null
            activeConnectionInfo = null
            activeControlChannel = null
            val controlSession = activeControlSession
            activeControlSession = null
            val controlEventJob = activeControlEventJob
            activeControlEventJob = null
            latestImuSample = null
            latestMagSample = null
            RuntimeHandles(
                streamJob = active,
                controlSession = controlSession,
                controlEventJob = controlEventJob
            )
        }
        handles.controlEventJob?.cancel()
        handles.controlSession?.close()
        if (awaitTermination) {
            handles.streamJob?.cancelAndJoin()
        } else {
            handles.streamJob?.cancel()
        }
        if (resetBiasState) {
            _biasState.value = XrBiasState.Inactive
        }
    }

    private suspend fun handleRuntimeEvent(
        event: HeadTrackingStreamEvent,
        startupSignal: CompletableDeferred<XrConnectionInfo>
    ) {
        when (event) {
            is HeadTrackingStreamEvent.BiasStateChanged -> {
                _biasState.value = event.state
            }

            is HeadTrackingStreamEvent.Connected -> {
                val info = XrConnectionInfo(
                    networkHandle = event.networkHandle,
                    interfaceName = event.interfaceName,
                    localSocket = event.localSocket,
                    remoteSocket = event.remoteSocket,
                    connectMs = event.connectMs
                )
                runtimeMutex.withLock {
                    activeConnectionInfo = info
                }
                _sessionState.value = XrSessionState.Calibrating(
                    connectionInfo = info,
                    calibrationSampleCount = 0,
                    calibrationTarget = 0
                )
            }

            is HeadTrackingStreamEvent.CalibrationProgress -> {
                val info = runtimeMutex.withLock { activeConnectionInfo } ?: return
                if (event.isComplete) {
                    _sessionState.value = XrSessionState.Streaming(info)
                } else {
                    _sessionState.value = XrSessionState.Calibrating(
                        connectionInfo = info,
                        calibrationSampleCount = event.calibrationSampleCount,
                        calibrationTarget = event.calibrationTarget
                    )
                }
            }

            is HeadTrackingStreamEvent.ReportAvailable -> {
                _advancedReports.tryEmit(event.report)
                val snapshot = runtimeMutex.withLock {
                    when (event.report.reportType) {
                        OneProReportType.IMU -> {
                            latestImuSample = XrImuSample(
                                gx = event.report.gx,
                                gy = event.report.gy,
                                gz = event.report.gz,
                                ax = event.report.ax,
                                ay = event.report.ay,
                                az = event.report.az,
                                deviceTimeNs = event.report.hmdTimeNanosDevice
                            )
                        }

                        OneProReportType.MAGNETOMETER -> {
                            latestMagSample = XrMagSample(
                                mx = event.report.mx,
                                my = event.report.my,
                                mz = event.report.mz,
                                deviceTimeNs = event.report.hmdTimeNanosDevice
                            )
                        }
                    }
                    XrSensorSnapshot(
                        imu = latestImuSample,
                        magnetometer = latestMagSample,
                        deviceId = event.report.deviceId,
                        temperatureCelsius = event.report.temperatureCelsius,
                        frameId = event.report.frameId,
                        imuId = event.report.imuId,
                        reportType = event.report.reportType,
                        imuDeviceTimeNs = latestImuSample?.deviceTimeNs,
                        magDeviceTimeNs = latestMagSample?.deviceTimeNs,
                        lastUpdatedSource = if (event.report.reportType == OneProReportType.IMU) {
                            XrSensorUpdateSource.IMU
                        } else {
                            XrSensorUpdateSource.MAG
                        }
                    )
                }
                _sensorData.value = snapshot
                if (!startupSignal.isCompleted) {
                    val connection = runtimeMutex.withLock { activeConnectionInfo }
                    if (connection != null) {
                        startupSignal.complete(connection)
                    }
                }
            }

            is HeadTrackingStreamEvent.TrackingSampleAvailable -> {
                _poseData.value = XrPoseSnapshot(
                    relativeOrientation = event.sample.relativeOrientation,
                    absoluteOrientation = event.sample.absoluteOrientation,
                    isCalibrated = event.sample.isCalibrated,
                    calibrationSampleCount = event.sample.calibrationSampleCount,
                    calibrationTarget = event.sample.calibrationTarget,
                    deltaTimeSeconds = event.sample.deltaTimeSeconds,
                    sourceDeviceTimeNs = event.sample.sourceDeviceTimeNs,
                    factoryGyroBias = event.sample.factoryGyroBias,
                    runtimeResidualGyroBias = event.sample.runtimeResidualGyroBias,
                    factoryAccelBias = event.sample.factoryAccelBias
                )
                val info = runtimeMutex.withLock { activeConnectionInfo }
                if (info != null && event.sample.isCalibrated) {
                    _sessionState.value = XrSessionState.Streaming(info)
                }
            }

            is HeadTrackingStreamEvent.DiagnosticsAvailable -> {
                _advancedDiagnostics.value = event.diagnostics
            }

            is HeadTrackingStreamEvent.StreamStopped -> {
                if (!startupSignal.isCompleted) {
                    startupSignal.completeExceptionally(
                        IllegalStateException("Stream stopped before startup completed: ${event.reason}")
                    )
                }
                cancelRuntimeSession(resetBiasState = true)
                _sessionState.value = XrSessionState.Stopped
            }

            is HeadTrackingStreamEvent.StreamError -> {
                handleRuntimeFailure(
                    code = XrSessionErrorCode.STREAM_ERROR,
                    message = event.error,
                    startupSignal = startupSignal
                )
                cancelRuntimeSession(resetBiasState = false)
            }
        }
    }

    private fun handleRuntimeFailure(
        code: XrSessionErrorCode,
        message: String,
        startupSignal: CompletableDeferred<XrConnectionInfo>
    ) {
        _sessionState.value = XrSessionState.Error(
            code = code,
            message = message,
            causeType = message.substringBefore(':').ifBlank { "Unknown" },
            recoverable = true
        )
        if (!startupSignal.isCompleted) {
            startupSignal.completeExceptionally(IllegalStateException(message))
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private suspend fun sendSetNumericControlCommand(
        magic: Int,
        value: Int
    ) {
        val request = XrControlPropertyWire.encodeSetNumericPropertyRequest(value)
        val response = sendControlTransaction(
            magic = magic,
            requestBody = request
        )
        XrControlPropertyWire.parseEmptyPropertyResponse(response)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private suspend fun sendGetPropertyControlCommand(magic: Int): ByteArray {
        return sendControlTransaction(
            magic = magic,
            requestBody = XrControlPropertyWire.encodeGetPropertyRequest()
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private suspend fun sendControlTransaction(
        magic: Int,
        requestBody: ByteArray,
        connectTimeoutMs: Int = 1500,
        requestTimeoutMs: Long = 1200L
    ): ByteArray = withContext(Dispatchers.IO) {
        val session = getOrCreateControlSession(
            connectTimeoutMs = connectTimeoutMs,
            requestTimeoutMs = requestTimeoutMs
        )
        session.sendTransaction(
            magic = magic,
            requestBody = requestBody,
            timeoutMs = requestTimeoutMs
        )
    }

    private fun createControlInboundJob(session: XrControlSession): Job {
        return clientScope.launch {
            session.inboundMessages.collect { inbound ->
                when (inbound) {
                    is XrControlInboundMessage.KeyStateChangeRaw -> {
                        val event = try {
                            XrControlInboundParser.parseKeyStateChange(inbound.payload)
                        } catch (_: Throwable) {
                            XrControlEvent.UnknownMessage(
                                magic = XrControlMagic.KEY_STATE_CHANGE,
                                transactionId = null,
                                payload = inbound.payload
                            )
                        }
                        _advancedControlEvents.tryEmit(event)
                    }

                    is XrControlInboundMessage.Unknown -> {
                        _advancedControlEvents.tryEmit(
                            XrControlEvent.UnknownMessage(
                                magic = inbound.magic,
                                transactionId = inbound.transactionId,
                                payload = inbound.payload
                            )
                        )
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private suspend fun getOrCreateControlSession(
        connectTimeoutMs: Int,
        requestTimeoutMs: Long
    ): XrControlSession = withContext(Dispatchers.IO) {
        if (connectTimeoutMs <= 0) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.INVALID_ARGUMENT,
                message = "Control connect timeout must be > 0"
            )
        }
        if (requestTimeoutMs <= 0L) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.INVALID_ARGUMENT,
                message = "Control request timeout must be > 0"
            )
        }

        val staleEventJob = runtimeMutex.withLock {
            val existing = activeControlSession
            if (existing != null && !existing.isClosed()) {
                return@withContext existing
            }
            activeControlSession = null
            val stale = activeControlEventJob
            activeControlEventJob = null
            stale
        }
        staleEventJob?.cancel()

        val candidates = networkCandidatesForHost(endpoint.host)
        val selected = preferredNetwork(endpoint.host, candidates)
            ?: throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.NETWORK_UNAVAILABLE,
                message = "No matching Android Network candidate for host ${endpoint.host}"
            )

        val startNanos = System.nanoTime()
        val socket = try {
            selected.network.socketFactory.createSocket().apply {
                soTimeout = 0
                connect(
                    java.net.InetSocketAddress(endpoint.host, endpoint.controlPort),
                    connectTimeoutMs
                )
            }
        } catch (t: Throwable) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.CONNECTION_FAILED,
                message = "Control connection failed for ${endpoint.host}:${endpoint.controlPort}",
                cause = t
            )
        }

        val info = XrControlSessionInfo(
            networkHandle = selected.network.networkHandle,
            interfaceName = selected.interfaceName,
            localSocket = "${socket.localAddress.hostAddress}:${socket.localPort}",
            remoteSocket = "${socket.inetAddress.hostAddress}:${socket.port}",
            connectMs = (System.nanoTime() - startNanos) / 1_000_000
        )
        val session = XrControlSession.open(
            socket = socket,
            info = info,
            defaultRequestTimeoutMs = requestTimeoutMs
        )
        val sessionInboundJob = createControlInboundJob(session)

        return@withContext runtimeMutex.withLock {
            val existing = activeControlSession
            if (existing == null || existing.isClosed()) {
                activeControlEventJob?.cancel()
                activeControlSession = session
                activeControlEventJob = sessionInboundJob
                session
            } else {
                sessionInboundJob.cancel()
                session.close()
                existing
            }
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    internal fun streamHeadTracking(
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

        emit(HeadTrackingStreamEvent.BiasStateChanged(XrBiasState.LoadingConfig))
        val trackerBiasConfig = try {
            loadTrackerBiasConfig().also { loaded ->
                emit(
                    HeadTrackingStreamEvent.BiasStateChanged(
                        XrBiasState.Active(
                            fsn = loaded.fsn,
                            glassesVersion = loaded.glassesVersion
                        )
                    )
                )
            }.config
        } catch (t: Throwable) {
            val errorState = classifyBiasActivationError(t)
            emit(HeadTrackingStreamEvent.BiasStateChanged(errorState))
            emit(
                HeadTrackingStreamEvent.StreamError(
                    "Bias activation failed code=${errorState.code} detail=${errorState.message}"
                )
            )
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
                biasConfig = trackerBiasConfig
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

                        val sensorSample = OneProTrackerSampleMapper.fromReport(report)

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
                                    isCalibrated = tracker.isCalibrated,
                                    sourceDeviceTimeNs = report.hmdTimeNanosDevice,
                                    factoryGyroBias = update.factoryGyroBias,
                                    runtimeResidualGyroBias = update.runtimeResidualGyroBias,
                                    factoryAccelBias = update.factoryAccelBias
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
                HeadTrackingStreamEvent.BiasStateChanged(
                    XrBiasState.Error(
                        code = XrBiasErrorCode.RUNTIME_ERROR,
                        message = "${t.javaClass.simpleName}:${t.message ?: "no-message"}"
                    )
                )
            )
            emit(
                HeadTrackingStreamEvent.StreamError(
                    "${t.javaClass.simpleName}:${t.message ?: "no-message"}"
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private data class LoadedTrackerBiasConfig(
        val fsn: String,
        val glassesVersion: Int,
        val config: OneProTrackerBiasConfig
    )

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private suspend fun loadTrackerBiasConfig(): LoadedTrackerBiasConfig {
        val deviceConfig = getConfig()
        return LoadedTrackerBiasConfig(
            fsn = deviceConfig.fsn,
            glassesVersion = deviceConfig.glassesVersion,
            config = OneProTrackerBiasConfig(
                accelBias = OneProTrackerSampleMapper.remapAccelBiasToTrackerFrame(
                    deviceConfig.imu.accelBias.toVector3f()
                ),
                gyroBiasTemperatureData = deviceConfig.imu.gyroBiasTemperatureData
            )
        )
    }

    private fun classifyBiasActivationError(error: Throwable): XrBiasState.Error {
        return when (error) {
            is XrControlProtocolException -> XrBiasState.Error(
                code = XrBiasErrorCode.TRANSPORT_ERROR,
                message = "${error.code}:${error.message ?: "no-message"}"
            )

            is XrDeviceConfigException -> when (error.code) {
                XrDeviceConfigErrorCode.PARSE_ERROR -> XrBiasState.Error(
                    code = XrBiasErrorCode.PARSE_ERROR,
                    message = error.message ?: "no-message"
                )

                XrDeviceConfigErrorCode.SCHEMA_VALIDATION_ERROR -> XrBiasState.Error(
                    code = XrBiasErrorCode.SCHEMA_VALIDATION_ERROR,
                    message = error.message ?: "no-message"
                )
            }

            else -> XrBiasState.Error(
                code = XrBiasErrorCode.RUNTIME_ERROR,
                message = "${error.javaClass.simpleName}:${error.message ?: "no-message"}"
            )
        }
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

}

private fun XrVector3d.toVector3f(): Vector3f {
    return Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
}
