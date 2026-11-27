package dev.tvsopen.session

import android.content.Context
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.DecodedFrame
import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.Field
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.transport.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Telemetry(
    val speedKmh: Int? = null,            // SM1 idx 2
    val odometerKm: Double? = null,       // SM1 idx 3..5, /10
    val fuelBars: Int? = null,            // SM1 idx 6, low nibble
    val averageSpeedKmh: Int? = null,     // SM1 idx 7
    val tripStatus: Int? = null,          // SM1 idx 8
    val topSpeedKmh: Int? = null,         // SM1 idx 9
    val throttlePercent: Int? = null,     // SM1 idx 10, /2
    val timeTo60Sec: Double? = null,      // SM1 idx 13, /10
    val tripFMeters: Int? = null,         // SM1 idx 14..16, /10
    val engineRpm: Int? = null,           // SM1 idx 17..18
    val serviceDue: Boolean? = null,      // SM2 idx 4
    val milOn: Boolean? = null,
    val milBlinkCode: Int? = null,
    val softwareVersion: String? = null,  // SM2 idx 7
    val avgEconomyKmpl: Int? = null,      // SM3 idx 8
    val instantEconomyKmpl: Int? = null,  // SM3 idx 9
    val distanceToEmptyKm: Int? = null,   // SM3 idx 11..12
    val engineTempC: Int? = null,         // SM4 idx 8
    val batteryVoltage: Int? = null,      // SM4 idx 11
    val lastFrameAt: Long = 0L,
    val odometerAtConnectKm: Double? = null,
) {
    fun connectedDistanceKm(): Double? {
        val start = odometerAtConnectKm ?: return null
        val now = odometerKm ?: return null
        val d = now - start
        return if (d >= 0) d else null
    }

    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        lastFrameAt > 0 && now - lastFrameAt < STALE_AFTER_MS

    companion object {
        const val STALE_AFTER_MS = 10_000L
        const val FUEL_BAR_MAX = 6
    }
}

data class HudState(
    val active: Boolean = false,
    val instructionTitle: String? = null,
    val street: String? = null,
    val distanceMeters: Int = -1,
    val etaMinutes: Int = 0,
    val pictogramId: Int? = null,
    val provider: String? = null,
    val framesSent: Int = 0,
    val lastError: String? = null,
)

data class ClusterAlert(
    val kind: Kind,
    val title: String,
    val subtitle: String = "",
    val key: String = "",
    val callerNumber: String? = null,
    val callerCountryCode: String? = null,
    val callerName: String? = null,
    val messageSender: String? = null,
    val messageBody: String? = null,
) {
    enum class Kind { CALL, MESSAGE }
}

data class ConnectionState1(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val model: VehicleModel = VehicleModel.UNKNOWN,
    val address: String? = null,
)

data class ParkedInfo(
    val atMillis: Long,
    val label: String? = null,
    val distanceMeters: Int? = null,
)

object Session {

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    private val _alert = MutableStateFlow<ClusterAlert?>(null)
    val alert: StateFlow<ClusterAlert?> = _alert.asStateFlow()

    private val _simCallActive = MutableStateFlow(false)
    private val _pendingMessage = MutableStateFlow<ClusterAlert?>(null)

    private val _connection = MutableStateFlow(ConnectionState1())
    val connection: StateFlow<ConnectionState1> = _connection.asStateFlow()

    private val _parked = MutableStateFlow<ParkedInfo?>(null)
    val parked: StateFlow<ParkedInfo?> = _parked.asStateFlow()

    private val _rideMode = MutableStateFlow(false)
    val rideMode: StateFlow<Boolean> = _rideMode.asStateFlow()

    @Volatile
    private var rideLog: RideLog? = null

    @Volatile
    private var lastDisconnectAt: Long = 0L

    private val _rides = MutableStateFlow<List<Ride>>(emptyList())
    val rides: StateFlow<List<Ride>> = _rides.asStateFlow()

    private val _totalRides = MutableStateFlow(0)
    val totalRides: StateFlow<Int> = _totalRides.asStateFlow()

    private val _totalRideDistanceKm = MutableStateFlow(0.0)
    val totalRideDistanceKm: StateFlow<Double> = _totalRideDistanceKm.asStateFlow()

    fun attachRideLog(context: android.content.Context) {
        if (rideLog != null) return
        synchronized(this) {
            if (rideLog != null) return
            val log = RideLog(context)
            rideLog = log
            _rides.value = log.rides.value
            _totalRides.value = log.totalRides
            _totalRideDistanceKm.value = log.totalDistanceKm
        }
    }

    private fun syncRideFlows() {
        val log = rideLog ?: return
        _rides.value = log.rides.value
        _totalRides.value = log.totalRides
        _totalRideDistanceKm.value = log.totalDistanceKm
    }

    private fun trackRideDistance() {
        val log = rideLog ?: return
        val distance = _telemetry.value.connectedDistanceKm() ?: return
        val before = log.rides.value
        log.update(distance)
        if (log.rides.value !== before) _rides.value = log.rides.value
    }

    fun setRideMode(on: Boolean) { _rideMode.value = on }

    fun onParked(info: ParkedInfo) { _parked.value = info }

    fun loadParked(context: Context) {
        val p = RidePrefs.load(context)
        val at = p.parkedAtMillis
        if (at > 0L) {
            _parked.value = ParkedInfo(atMillis = at, label = p.parkedLabel, distanceMeters = null)
        }
    }

    fun reset() {
        _telemetry.value = Telemetry()
        _hud.value = HudState()
    }

    fun onConnection(state: ConnectionState, model: VehicleModel, address: String?) {
        val previous = _connection.value
        val wasConnected = previous.state == ConnectionState.CONNECTED
        val isConnected = state == ConnectionState.CONNECTED
        val now = System.currentTimeMillis()

        if (isConnected && !wasConnected) {
            val isBriefReconnect = lastDisconnectAt > 0L &&
                now - lastDisconnectAt < RIDE_DISCONNECT_GRACE_MS
            if (!isBriefReconnect && lastDisconnectAt > 0L) {
                rideLog?.finish(lastDisconnectAt)
                syncRideFlows()
                _telemetry.value = _telemetry.value.copy(odometerAtConnectKm = null)
            }
            lastDisconnectAt = 0L
            android.util.Log.i("Session", "link restored (brief=$isBriefReconnect)")
        }
        if (!isConnected && wasConnected) {
            lastDisconnectAt = now
            android.util.Log.i("Session", "link lost; ride finalization pending")
        }
        _connection.value = ConnectionState1(state, model, address)
    }

    @Synchronized
    fun finalizePendingRide(now: Long = System.currentTimeMillis()): Boolean {
        val disconnectedAt = lastDisconnectAt
        if (disconnectedAt <= 0L || now - disconnectedAt < RIDE_DISCONNECT_GRACE_MS) return false
        if (_connection.value.state == ConnectionState.CONNECTED) return false
        rideLog?.finish(disconnectedAt)
        _telemetry.value = _telemetry.value.copy(odometerAtConnectKm = null)
        lastDisconnectAt = 0L
        syncRideFlows()
        android.util.Log.i("Session", "pending ride finalized after BLE grace period")
        return true
    }

    fun onFrame(frame: DecodedFrame) {
        val now = System.currentTimeMillis()

        fun scaled(name: Field, div: Double): Double? =
            frame.field(name)?.let { it / div }

        val cur = _telemetry.value
        val fuelLowNibble = DriverRegistry.forModel(_connection.value.model)
            .capabilities.fuelLowNibble
        _telemetry.value = when (frame.dataId) {
            0x10 -> {
                val speed = frame.field(Field.SPEED)?.toInt() ?: cur.speedKmh
                val sw = frame.field(Field.SWITCH_STATUS)?.toInt() ?: 0
                val odo = scaled(Field.ODOMETER, 10.0) ?: cur.odometerKm
                val movingOrIgnitionOn = (speed ?: 0) > 0 || sw == 0x80
                val hasOpenRide = _rides.value.any { it.inProgress }
                val baselineOdo = when {
                    sw == 0x40 -> cur.odometerAtConnectKm ?: odo
                    movingOrIgnitionOn && !hasOpenRide -> odo
                    else -> cur.odometerAtConnectKm ?: odo
                }
                val updated = cur.copy(
                    speedKmh = speed,
                    odometerKm = odo,
                    odometerAtConnectKm = baselineOdo,
                    fuelBars = frame.field(Field.FUEL_LEVEL)?.toInt()
                        ?.let { if (fuelLowNibble) it and 0x0F else it }
                        ?.takeIf { it in 0..Telemetry.FUEL_BAR_MAX },
                    averageSpeedKmh = frame.field(Field.AVERAGE_SPEED)?.toInt() ?: cur.averageSpeedKmh,
                    tripStatus = frame.field(Field.TRIP_STATUS)?.toInt() ?: cur.tripStatus,
                    topSpeedKmh = frame.field(Field.TOP_SPEED)?.toInt() ?: cur.topSpeedKmh,
                    throttlePercent = scaled(Field.THROTTLE_POSITION, 2.0)?.toInt()
                        ?: cur.throttlePercent,
                    timeTo60Sec = scaled(Field.TIME_60KMPH, 10.0)
                        ?: cur.timeTo60Sec,
                    tripFMeters = scaled(Field.TRIP_F_METER, 10.0)?.toInt() ?: cur.tripFMeters,
                    engineRpm = frame.field(Field.ENGINE_RPM)?.toInt() ?: cur.engineRpm,
                    lastFrameAt = now,
                )
                _telemetry.value = updated
                // Start from the first fresh moving/ignition frame, not GATT connect.
                if (movingOrIgnitionOn && !hasOpenRide) {
                    rideLog?.start(now)
                    syncRideFlows()
                    _telemetry.value = updated.copy(odometerAtConnectKm = odo)
                }
                // Capture the final odometer before an ignition-off finish.
                trackRideDistance()
                if (sw == 0x40) {
                    rideLog?.finish(now)
                    syncRideFlows()
                    _telemetry.value = _telemetry.value.copy(odometerAtConnectKm = null)
                }
                updated
            }

            0x11 -> cur.copy(
                serviceDue = (frame.field(Field.SERVICE_REMINDER)?.toInt()?.let { it != 0 })
                    ?: cur.serviceDue,
                milOn = (frame.field(Field.VEHICLE_DIAGNOSTICS)?.toInt()
                    ?.let { (it and 0x04) != 0 }) ?: cur.milOn,
                milBlinkCode = frame.field(Field.MIL_BLINK_CODE)?.toInt()
                    ?: cur.milBlinkCode,
                softwareVersion = frame.field(Field.SOFTWARE_VERSION)?.toInt()
                    ?.let { "0x%X".format(it) } ?: cur.softwareVersion,
                lastFrameAt = now,
            )

            0x19 -> cur.copy(
                avgEconomyKmpl = frame.field(Field.AVERAGE_FUEL_ECONOMY)?.toInt()
                    ?: cur.avgEconomyKmpl,
                instantEconomyKmpl = frame.field(Field.INSTANTANEOUS_FUEL_ECONOMY)?.toInt()
                    ?: cur.instantEconomyKmpl,
                distanceToEmptyKm = frame.field(Field.DISTANCE_TO_EMPTY)?.toInt()
                    ?: cur.distanceToEmptyKm,
                lastFrameAt = now,
            )

            0x18 -> cur.copy(
                engineTempC = frame.field(Field.ENGINE_TEMPRATURE)?.toInt() ?: cur.engineTempC,
                batteryVoltage = frame.field(Field.BATTERY_VOLTAGE)?.toInt() ?: cur.batteryVoltage,
                lastFrameAt = now,
            )

            else -> cur.copy(lastFrameAt = now)
        }
    }

    fun onHud(transform: (HudState) -> HudState) {
        _hud.update(transform)
    }

    fun onAlert(alert: ClusterAlert?) {
        when (alert?.kind) {
            ClusterAlert.Kind.MESSAGE -> {
                // Never let a message notification replace an active call.
                // Keep the newest message and surface it after the call ends.
                if (_alert.value?.kind == ClusterAlert.Kind.CALL) {
                    _pendingMessage.value = alert
                    return
                }
            }
            ClusterAlert.Kind.CALL -> {
                // A call takes priority over a message already on screen.
                _alert.value?.takeIf { it.kind == ClusterAlert.Kind.MESSAGE }?.let {
                    _pendingMessage.value = it
                }
            }
            null -> Unit
        }
        _alert.value = alert
    }

    fun setSimCallActive(active: Boolean) {
        _simCallActive.value = active
    }

    fun clearAlert(key: String) {
        if (_pendingMessage.value?.key == key) _pendingMessage.value = null

        val current = _alert.value ?: return
        if (current.key != key) return
        // Samsung removes/reposts its in-call notification when a call is
        // answered. The SIM state, not that notification lifecycle, owns the
        // lifetime of a mirrored cellular call.
        if (current.kind == ClusterAlert.Kind.CALL && _simCallActive.value) return

        if (current.kind == ClusterAlert.Kind.CALL) {
            _alert.value = _pendingMessage.value
            _pendingMessage.value = null
        } else {
            _alert.value = null
        }
    }

    fun clearActiveCall() {
        _simCallActive.value = false
        if (_alert.value?.kind == ClusterAlert.Kind.CALL) {
            _alert.value = _pendingMessage.value
            _pendingMessage.value = null
        }
    }

    private const val RIDE_DISCONNECT_GRACE_MS = 15_000L
}
