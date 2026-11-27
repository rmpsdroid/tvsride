package dev.tvsopen.transport

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.tvsopen.log.Log
import dev.tvsopen.nav.NavInstruction
import dev.tvsopen.nav.NavigationBridge
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.ClusterFrames
import dev.tvsopen.protocol.DashState
import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.OutboundId
import dev.tvsopen.protocol.PacketBuilder
import dev.tvsopen.protocol.ProtocolDriver
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.session.ClusterAlert
import dev.tvsopen.session.ParkedInfo
import dev.tvsopen.session.Session
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the BLE link and the HUD alive for as long as the session runs.
 */
class RideService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridge: NavigationBridge? = null
    private var ble: ScooterBleService? = null
    private var linkJob: Job? = null
    private var lastModel: VehicleModel = VehicleModel.NTORQ
    private var lastAddress: String? = null
    private var driver: ProtocolDriver? = null
    private var mediaKeys: MediaKeys? = null
    private var callButtons: CallButtons? = null
    private var lastCallSwitch = 0
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var tearingDown = false
    private var linkWakeLock: android.os.PowerManager.WakeLock? = null
    private var hadLiveLink = false
    private var telephonyCallMonitor: TelephonyCallMonitor? = null
    private var disconnectFinalizeJob: Job? = null

    private fun holdWakeLock() {
        if (linkWakeLock?.isHeld == true) return
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        linkWakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK, "TVSRide:link",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "wake lock held for link lifetime")
    }

    private fun releaseWakeLock() {
        runCatching { if (linkWakeLock?.isHeld == true) linkWakeLock?.release() }
        linkWakeLock = null
    }

    override fun onCreate() {
        super.onCreate()
        Log.enableFileSink(java.io.File(filesDir, "logs"))
        Session.attachRideLog(applicationContext)
        telephonyCallMonitor = TelephonyCallMonitor(applicationContext) { callState ->
            when (callState) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    val mirrorCalls = RidePrefs.load(applicationContext).mirrorPhoneCalls
                    Session.setSimCallActive(mirrorCalls)
                    if (mirrorCalls && Session.alert.value?.kind != ClusterAlert.Kind.CALL) {
                        Session.onAlert(
                            ClusterAlert(
                                kind = ClusterAlert.Kind.CALL,
                                title = "Incoming call",
                                key = "telephony-sim-call",
                                callerName = "Incoming call",
                            ),
                        )
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> Session.clearActiveCall()
                // OFFHOOK means the call was answered or outgoing. Keep the
                // alert until IDLE so the cluster is not cleared mid-call.
                else -> Unit
            }
        }.also { it.start() }
        Log.i(TAG, "service created (v${dev.tvsopen.BuildConfig.VERSION_NAME}, sdk ${android.os.Build.VERSION.SDK_INT})")

        try {
            startForeground(NOTIF_ID, buildNotification("Starting…"))
        } catch (e: SecurityException) {
            Log.e(TAG, "cannot enter foreground: ${e.message}")
            Log.e(TAG, "BLUETOOTH_CONNECT/SCAN not granted; stopping rather than idling")
            stopSelf()
            return
        }
    }

    private fun enterForeground() {
        runCatching { startForeground(NOTIF_ID, buildNotification("Connecting…")) }
            .onFailure { Log.w(TAG, "foreground still refused: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (address.isNullOrBlank()) {
                    Log.w(TAG, "connect requested without an address; ignoring")
                } else {
                    val model = intent.getStringExtra(EXTRA_MODEL)
                        ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
                        ?: VehicleModel.NTORQ
                    connect(address, model)
                }
            }
            ACTION_FIND_MY -> bridge?.setFindMyScooter(true)
            ACTION_STOP_FIND_MY -> bridge?.setFindMyScooter(false)
            ACTION_START_NAV -> bridge?.startNavigation()
            ACTION_STOP_NAV -> bridge?.stopNavigation()
            ACTION_UPDATE_RIDER_NAME -> sendRiderNameIfConnected()
            ACTION_SET_BRIGHTNESS -> {
                val level = intent.getIntExtra(EXTRA_VALUE, 3)
                sendBrightnessIfConnected(level)
            }
            ACTION_SET_CALIBRATION -> {
                val cmd = intent.getIntExtra(EXTRA_CAL_CMD, -1)
                val seconds = intent.getIntExtra(EXTRA_VALUE, 0)
                if (cmd >= 0) sendCalibrationIfConnected(cmd, seconds)
            }
            ACTION_DISCONNECT -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            null -> Unit
            else -> resumeLastConnection()
        }
        return START_STICKY
    }

    private fun resumeLastConnection() {
        val prefs = RidePrefs.load(this)
        val address = prefs.lastAddress
        if (address.isNullOrBlank()) {
            Log.i(TAG, "sticky restart with no remembered cluster; idling")
            return
        }
        if (!prefs.resumeOnBoot && ble?.let { it.state.value == ConnectionState.CONNECTED } != true) {
            Log.i(TAG, "sticky restart; not resuming (resume-on-boot is off)")
            return
        }
        val model = prefs.lastModelId
            ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
            ?: VehicleModel.NTORQ
        Log.i(TAG, "sticky restart; resuming $address")
        connect(address, model)
    }

    fun connect(address: String, model: VehicleModel, fromReconnect: Boolean = false): Boolean {
        linkJob?.cancel()
        linkJob = null
        if (!fromReconnect) {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
        }
        tearingDown = false
        bridge?.stopKeepalive()
        bridge?.stopNavigation()

        enterForeground()
        ble?.let { old ->
            scope.launch(NonCancellable) {
                runCatching { old.disconnect() }
            }
        }
        ble = null
        val transport = ScooterBleService(applicationContext).also { ble = it }
        val driver = if (model.sharesNtorqProtocol()) {
            NtorqDriver()
        } else {
            DriverRegistry.forModel(model)
        }
        val b = NavigationBridge(transport, scope, driver)
        bridge = b
        this.driver = driver
        mediaKeys = mediaKeys ?: MediaKeys(applicationContext)
        callButtons = callButtons ?: CallButtons(applicationContext)
        lastCallSwitch = 0
        lastModel = model
        lastAddress = address
        RidePrefs.load(this).updateLastConnection(address, model.name)

        Session.onConnection(ConnectionState.CONNECTING, model, address)
        Log.i(TAG, "connecting to ${model.displayName} at $address")
        notify("Connecting to ${model.displayName}…")

        val started = try {
            transport.connect(address)
        } catch (e: SecurityException) {
            Log.e(TAG, "connect refused (permission?): ${e.message}")
            false
        }
        if (!started) {
            val alreadyUp = Session.connection.value.state == ConnectionState.CONNECTED
            val reason = when {
                transport.adapter == null -> "no Bluetooth adapter"
                alreadyUp -> "already connected"
                else -> "retrying BLE link"
            }
            Log.w(TAG, "connect not started: $reason")
            notify("Waiting for scooter ($reason)…")
            Session.onConnection(ConnectionState.DISCONNECTED, model, address)
            if (!alreadyUp && transport.adapter != null) {
                scope.launch {
                    runCatching { transport.disconnect() }
                    if (!tearingDown) scheduleReconnect(address, model)
                }
            }
            return false
        }

        linkJob = scope.launch {
            launch {
                delay(CONNECT_ATTEMPT_TIMEOUT_MS)
                if (transport.state.value == ConnectionState.CONNECTING ||
                    transport.state.value == ConnectionState.SCANNING
                ) {
                    Log.w(TAG, "GATT setup timed out before service/notification readiness")
                    runCatching { transport.disconnect() }
                }
            }
            launch {
                Session.hud.collect { hud ->
                    b.onInstruction(
                        NavInstruction(
                            title = hud.instructionTitle.orEmpty(),
                            street = hud.street.orEmpty(),
                            distanceMeters = hud.distanceMeters,
                            etaMinutes = hud.etaMinutes,
                            pictogram = hud.pictogramId,
                            provider = hud.provider.orEmpty(),
                        ).takeIf { hud.active },
                    )
                }
            }
            launch {
                transport.frames.collect { frame ->
                    if (frame.direction != BleFrame.Direction.INBOUND) return@collect
                    val response = driver.buildChallengeResponse(frame.bytes)
                    if (response != null) {
                        Log.i(TAG, "answering cluster challenge")
                        transport.write(response)
                    } else {
                        val decoded = driver.decode(frame.bytes)
                        if (decoded != null) {
                            Session.onFrame(decoded)
                            handleClusterButton(driver, decoded)
                        }
                    }
                }
            }
            launch {
                transport.state.collect { st ->
                    Session.onConnection(st, model, address)
                    Log.d(TAG, "state -> $st")
                    when (st) {
                        ConnectionState.CONNECTED -> {
                            disconnectFinalizeJob?.cancel()
                            disconnectFinalizeJob = null
                            reconnectJob?.cancel()
                            reconnectJob = null
                            reconnectAttempt = 0
                            hadLiveLink = true
                            notify("Connected to ${model.displayName}")
                            holdWakeLock()
                            if (RidePrefs.load(this@RideService).navigationMirroring) {
                                b.startNavigation()
                            }
                            applySavedClusterSettings()
                            if (driver.capabilities.keepalive) {
                                b.startKeepalive {
                                    DashState(
                                        timeMillis = System.currentTimeMillis(),
                                        batteryBars = batteryBars(),
                                        signalBars = 4,
                                    )
                                }
                            }
                        }
                        ConnectionState.DISCONNECTED -> {
                            releaseWakeLock()
                            notify("Disconnected")
                            disconnectFinalizeJob?.cancel()
                            disconnectFinalizeJob = scope.launch {
                                delay(RIDE_DISCONNECT_GRACE_MS)
                                if (Session.finalizePendingRide()) {
                                    recordParkedIfPossible()
                                }
                            }
                            scheduleReconnect(address, model)
                        }
                        else -> Unit
                    }
                }
            }
            launch {
                var lastAlert: ClusterAlert? = null
                Session.alert.collect { alert ->
                    val t = ble ?: return@collect
                    if (t.state.value != ConnectionState.CONNECTED) {
                        lastAlert = alert
                        return@collect
                    }
                    if (lastAlert?.kind == ClusterAlert.Kind.CALL &&
                        alert?.kind != ClusterAlert.Kind.CALL
                    ) {
                        sendCallEnded(driver, t)
                    }
                    if (alert != null) {
                        sendAlert(driver, t, alert)
                    }
                    lastAlert = alert
                }
            }
            launch {
                b.hud.collect { h ->
                    Session.onHud { it.copy(framesSent = h.framesSent, lastError = h.lastError) }
                }
            }
        }
        return true
    }

    private fun batteryBars(): Int {
        val pct = android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
        val level = getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(pct) ?: -1
        if (level < 0) return 0
        return (level / 20).coerceIn(0, 5)
    }

    /**
     * Pushes saved rider greeting, backlight brightness, and calibration timers
     * sequentially right after connection so user settings persist across ignition cycles.
     */
    private fun applySavedClusterSettings() {
        val prefs = RidePrefs.load(this)
        val transport = ble ?: return
        val drv = driver ?: return
        if (transport.state.value != ConnectionState.CONNECTED) return

        scope.launch {
            val name = prefs.riderName
            if (!name.isNullOrBlank()) {
                drv.buildRiderName(name)?.let { frame ->
                    if (transport.write(frame)) Log.i(TAG, "rider name sent (${name.length} chars)")
                    delay(150)
                }
            }

            if (drv.capabilities.brightness) {
                val lvl = prefs.clusterBrightness
                drv.buildBrightness(lvl)?.let { frame ->
                    if (transport.write(frame)) Log.i(TAG, "auto-applied cluster brightness=$lvl")
                    delay(150)
                }
            }

            if (drv.capabilities.calibration) {
                prefs.followMeTimerSec?.let { sec ->
                    val frame = drv.buildCalibration(
                        ClusterFrames.CalibrationCommand.FOLLOW_ME_HEADLAMP_TIMER,
                        sec,
                    ) ?: ClusterFrames.calibration(
                        ClusterFrames.CalibrationCommand.FOLLOW_ME_HEADLAMP_TIMER,
                        sec,
                    )
                    if (transport.write(frame)) Log.i(TAG, "auto-applied follow-me headlamp=${sec}s")
                    delay(150)
                }

                prefs.idleStopTimerSec?.let { sec ->
                    val frame = drv.buildCalibration(
                        ClusterFrames.CalibrationCommand.IDLE_START_STOP_TIMER,
                        sec,
                    ) ?: ClusterFrames.calibration(
                        ClusterFrames.CalibrationCommand.IDLE_START_STOP_TIMER,
                        sec,
                    )
                    if (transport.write(frame)) Log.i(TAG, "auto-applied idle start-stop=${sec}s")
                }
            }
        }
    }

    private fun sendRiderNameIfConnected() {
        val name = RidePrefs.load(this).riderName
        val transport = ble
        val drv = driver
        if (name.isNullOrBlank() || transport == null || drv == null) return
        if (transport.state.value != ConnectionState.CONNECTED) {
            Log.i(TAG, "rider name saved; will send on next connection")
            return
        }
        val frame = drv.buildRiderName(name) ?: return
        scope.launch {
            if (transport.write(frame)) Log.i(TAG, "rider name sent (${name.length} chars)")
        }
    }

    private fun sendBrightnessIfConnected(level: Int) {
        val transport = ble ?: return
        val drv = driver ?: return
        if (transport.state.value != ConnectionState.CONNECTED) return
        val frame = drv.buildBrightness(level) ?: return
        scope.launch {
            if (transport.write(frame)) Log.i(TAG, "cluster brightness set to $level")
        }
    }

    private fun sendCalibrationIfConnected(command: Int, value: Int) {
        val transport = ble ?: return
        val drv = driver ?: return
        if (transport.state.value != ConnectionState.CONNECTED) return
        val frame = drv.buildCalibration(command, value)
            ?: ClusterFrames.calibration(command, value)
        scope.launch {
            if (transport.write(frame)) Log.i(TAG, "calibration cmd=$command val=$value sent")
        }
    }

    private suspend fun sendAlert(
        driver: ProtocolDriver,
        transport: ScooterBleService,
        alert: ClusterAlert,
    ) {
        when (alert.kind) {
            ClusterAlert.Kind.CALL -> {
                val number = alert.callerNumber.orEmpty()
                val name = alert.callerName
                    ?: alert.title.takeIf { it.isNotBlank() && it != number }
                val frames = driver.buildCallerFrames(number, alert.callerCountryCode, name)
                if (frames.isNotEmpty()) {
                    var written = 0
                    for (frame in frames) {
                        if (transport.write(frame)) written++
                    }
                    if (written == frames.size) {
                        Log.i(TAG, "cluster call alert written ($written caller frames; details redacted)")
                    } else {
                        Log.w(TAG, "cluster call alert write incomplete ($written/${frames.size} frames)")
                    }
                } else {
                    val text = listOfNotNull(
                        name?.takeIf { it.isNotBlank() },
                        number.takeIf { it.isNotBlank() },
                    ).joinToString(" ").ifBlank { alert.title }
                    val frame = driver.buildTextRow(0, text)
                        ?: PacketBuilder.textRow(OutboundId.CALLER, text)
                    if (transport.write(frame)) {
                        Log.i(TAG, "cluster generic call text written")
                    } else {
                        Log.w(TAG, "cluster generic call text write failed")
                    }
                }
            }
            ClusterAlert.Kind.MESSAGE -> {
                val text = alert.title.take(17)
                Log.i(TAG, "cluster message alert: '$text'")
                val frame = driver.buildSms(text) ?: PacketBuilder.textRow(OutboundId.SMS, text)
                transport.write(frame)
            }
        }
    }

    private suspend fun sendCallEnded(driver: ProtocolDriver, transport: ScooterBleService) {
        val frames = driver.buildCallEndedFrames()
        if (frames.isEmpty()) {
            Log.d(TAG, "driver has no caller-clear frame")
            return
        }
        var written = 0
        for (frame in frames) {
            if (transport.write(frame)) written++
        }
        if (written == frames.size) {
            Log.i(TAG, "cluster call alert cleared ($written caller-clear frames)")
        } else {
            Log.w(TAG, "cluster call clear incomplete ($written/${frames.size} frames)")
        }
    }

    private fun recordParkedIfPossible() {
        if (!hadLiveLink) return
        hadLiveLink = false
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            Log.i(TAG, "parked spot not recorded: no location permission")
            return
        }
        val lm = getSystemService(LocationManager::class.java) ?: return
        val loc = bestLastLocation(lm) ?: run {
            Log.i(TAG, "parked spot not recorded: no recent location fix")
            return
        }
        val label = String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
        val info = ParkedInfo(System.currentTimeMillis(), label, null)
        RidePrefs.load(this).saveParked(info.atMillis, info.label)
        Session.onParked(info)
        Log.i(TAG, "parked spot saved: $label")
    }

    @SuppressLint("MissingPermission")
    private fun bestLastLocation(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var best: Location? = null
        for (provider in providers) {
            val loc = runCatching {
                val enabled = provider == LocationManager.PASSIVE_PROVIDER || lm.isProviderEnabled(provider)
                if (enabled) lm.getLastKnownLocation(provider) else null
            }.getOrNull() ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        return best
    }

    private fun handleClusterButton(driver: ProtocolDriver, frame: dev.tvsopen.protocol.DecodedFrame) {
        if (frame.dataId == 0x6B && driver.capabilities.musicKeys) {
            val cmd = frame.field(dev.tvsopen.protocol.Field.MUSIC_COMMAND)?.toInt() ?: return
            mediaKeys?.handle(cmd)
            return
        }
        if (frame.dataId == 0x10 && driver.capabilities.callButtons) {
            val sw = frame.field(dev.tvsopen.protocol.Field.SWITCH_STATUS)?.toInt() ?: 0
            if (sw in 1..4 && sw != lastCallSwitch) {
                callButtons?.handle(sw)
            }
            lastCallSwitch = sw
        }
    }

    private fun scheduleReconnect(address: String, model: VehicleModel) {
        if (tearingDown) return
        val currentState = ble?.state?.value
        if (currentState == ConnectionState.CONNECTED ||
            currentState == ConnectionState.CONNECTING ||
            currentState == ConnectionState.SCANNING
        ) return
        if (reconnectJob?.isActive == true) return

        val attempt = reconnectAttempt
        val wait = when {
            attempt < RECONNECT_DELAYS_MS.size -> RECONNECT_DELAYS_MS[attempt]
            attempt < 34 -> 60_000L
            else -> 180_000L
        }
        reconnectAttempt = attempt + 1
        Log.i(TAG, "reconnect #${attempt + 1} scheduled in ${wait}ms")
        reconnectJob = scope.launch {
            delay(wait)
            if (tearingDown) return@launch
            val active = ble?.state?.value
            if (active == ConnectionState.CONNECTED ||
                active == ConnectionState.CONNECTING ||
                active == ConnectionState.SCANNING
            ) return@launch

            val started = connect(address, model, fromReconnect = true)
            if (!started) {
                Log.w(TAG, "reconnect attempt #${attempt + 1} could not start")
                reconnectJob = null
                if (!tearingDown) scheduleReconnect(address, model)
                return@launch
            }

            val transport = ble
            val terminal = if (transport == null) null else withTimeoutOrNull(CONNECT_ATTEMPT_TIMEOUT_MS) {
                transport.state.first {
                    it == ConnectionState.CONNECTED || it == ConnectionState.DISCONNECTED
                }
            }
            if (terminal != ConnectionState.CONNECTED) {
                Log.w(TAG, "reconnect attempt #${attempt + 1} timed out or disconnected")
                runCatching { transport?.disconnect() }
                reconnectJob = null
                if (!tearingDown) scheduleReconnect(address, model)
            } else {
                Log.i(TAG, "reconnect attempt #${attempt + 1} connected")
                reconnectJob = null
            }
        }
    }

    private fun teardown() {
        tearingDown = true
        reconnectJob?.cancel()
        reconnectJob = null
        disconnectFinalizeJob?.cancel()
        disconnectFinalizeJob = null
        telephonyCallMonitor?.stop()
        telephonyCallMonitor = null
        Session.clearActiveCall()
        recordParkedIfPossible()
        linkJob?.cancel()
        linkJob = null
        bridge?.stopKeepalive()
        bridge?.stopNavigation()
        releaseWakeLock()
        driver = null
        Session.onConnection(ConnectionState.DISCONNECTED, lastModel, lastAddress)
        val transport = ble
        ble = null
        if (transport != null) {
            scope.launch(NonCancellable) {
                runCatching { transport.disconnect() }
                    .onFailure { Log.w(TAG, "disconnect failed: ${it.message}") }
            }
        }
        bridge = null
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ride", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, dev.tvsopen.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("TVSRide")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "RideService"
        private const val RIDE_DISCONNECT_GRACE_MS = 15_000L
        private const val CONNECT_ATTEMPT_TIMEOUT_MS = 20_000L
        const val CHANNEL = "ride"
        const val NOTIF_ID = 1
        const val ACTION_CONNECT = "dev.tvsopen.CONNECT"
        const val ACTION_FIND_MY = "dev.tvsopen.FIND_MY"
        const val ACTION_STOP_FIND_MY = "dev.tvsopen.STOP_FIND_MY"
        const val ACTION_START_NAV = "dev.tvsopen.START_NAV"
        const val ACTION_STOP_NAV = "dev.tvsopen.STOP_NAV"
        const val ACTION_UPDATE_RIDER_NAME = "dev.tvsopen.UPDATE_RIDER_NAME"
        const val ACTION_SET_BRIGHTNESS = "dev.tvsopen.SET_BRIGHTNESS"
        const val ACTION_SET_CALIBRATION = "dev.tvsopen.SET_CALIBRATION"
        const val EXTRA_CAL_CMD = "cal_cmd"
        const val EXTRA_VALUE = "value"
        const val ACTION_DISCONNECT = "dev.tvsopen.DISCONNECT"

        const val EXTRA_ADDRESS = "address"
        const val EXTRA_MODEL = "model"

        private val RECONNECT_DELAYS_MS = longArrayOf(
            5_000, 10_000, 20_000, 40_000,
            60_000, 60_000, 60_000, 60_000, 60_000, 60_000,
        )
    }
}
