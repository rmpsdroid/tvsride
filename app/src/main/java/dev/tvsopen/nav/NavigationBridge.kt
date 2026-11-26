package dev.tvsopen.nav

import android.util.Log
import dev.tvsopen.protocol.DashState
import dev.tvsopen.protocol.Maneuvers
import dev.tvsopen.protocol.NavStep
import dev.tvsopen.protocol.OutboundId
import dev.tvsopen.protocol.Pictograms
import dev.tvsopen.protocol.ProtocolDriver
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.transport.ScooterBleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HudState(
    val active: Boolean = false,
    val instruction: NavInstruction? = null,
    val framesSent: Int = 0,
    val lastError: String? = null,
    val navigationDataId: Int? = null,
)

class NavigationBridge(
    private val ble: ScooterBleService,
    private val scope: CoroutineScope,
    private val driver: ProtocolDriver,
) {

    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    private var controlJob: Job? = null
    private var pingJob: Job? = null

    var navigationDataId: Int = OutboundId.NAVIGATION_CONTROL

    private val isJupiter: Boolean
        get() = driver.model == VehicleModel.JUPITER_110 || driver.model == VehicleModel.JUPITER_125

    var textRowChars: Int = if (isJupiter) RowChars.JUPITER else RowChars.NTORQ
    var secondTextRowChars: Int = if (isJupiter) RowChars.JUPITER else RowChars.NTORQ_LINE_2
    var firstTextRowChars: Int = if (isJupiter) RowChars.JUPITER else RowChars.NTORQ_LINE_1

    var controlIntervalMs: Long = 1500
    var textSwapIntervalMs: Long = 3000
    var pingIntervalMs: Long = 2000

    private var findMy = false
    private var scrollTick = 0
    private var lastInstructionKey: String? = null

    fun startKeepalive(dash: () -> DashState) {
        if (pingJob?.isActive == true) return
        pingJob = scope.launch {
            while (isActive) {
                val state = dash().copy(findMyScooter = findMy)
                driver.buildPing(state)?.let { ble.write(it) }
                delay(pingIntervalMs)
            }
        }
    }

    fun stopKeepalive() {
        pingJob?.cancel()
        pingJob = null
    }

    fun setFindMyScooter(enabled: Boolean) {
        findMy = enabled
        Log.i(TAG, "find-my-scooter -> $enabled")
    }

    fun startNavigation() {
        if (!driver.capabilities.navigationHud) {
            Log.w(TAG, "${driver.model.displayName} nav not enabled for this driver")
            return
        }
        if (controlJob?.isActive == true) return
        var tickCount = 0

        controlJob = scope.launch {
            _hud.value = _hud.value.copy(active = true, navigationDataId = navigationDataId)
            while (isActive) {
                val step = _hud.value.instruction
                if (step != null) {
                    if (step.distanceMeters < 0) {
                        delay(controlIntervalMs)
                        continue
                    }
                    val key = "${step.title}|${step.street}|${step.distanceMeters}"
                    if (key != lastInstructionKey) {
                        lastInstructionKey = key
                        scrollTick = 0
                        tickCount = 0
                    }
                    val nav = step.toNavStep()
                    val maneuver = if (isJupiter) {
                        jupiterCleanLine(step, firstTextRowChars, scrollTick)
                    } else {
                        step.displayLine(firstTextRowChars, scrollTick)
                    }
                    val metrics = step.metricsLine(secondTextRowChars, scrollTick)

                    val twoRows = driver.buildNavTextSecondary(nav.copy(streetName = maneuver)) != null &&
                        maneuver.isNotBlank() && metrics.isNotBlank()

                    // 1. Always send navigation control (0x5A 0x4E) every 1.5s as liveness heartbeat.
                    val control = nav.copy(rows = if (twoRows) 2 else 1)
                    driver.buildNavControl(control)?.let { frame ->
                        if (ble.write(frame)) {
                            _hud.value = _hud.value.copy(
                                framesSent = _hud.value.framesSent + 1, lastError = null,
                            )
                        } else {
                            _hud.value = _hud.value.copy(lastError = "nav frame not written")
                        }
                    }

                    // 2. Always send Jupiter pictogram (0x5B 0x50) with 50ms BLE spacing.
                    if (isJupiter) {
                        delay(50)
                        val picName = jupiterPictogramName(step.pictogram, step.title)
                        driver.buildPictogram(picName)?.let { ble.write(it) }
                    }

                    // 3. Send text row(s) with 50ms spacing so GATT never drops frames.
                    delay(50)
                    if (twoRows) {
                        driver.buildNavText(nav.copy(streetName = maneuver))?.let { ble.write(it) }
                        delay(50)
                        driver.buildNavTextSecondary(
                            nav.copy(streetName = metrics),
                        )?.let { ble.write(it) }
                    } else {
                        val showMetrics = (tickCount % 3 == 2) && metrics.isNotBlank()
                        val line = if (showMetrics) metrics else maneuver
                        driver.buildNavText(nav.copy(streetName = line))?.let { ble.write(it) }
                    }
                    tickCount++
                    scrollTick++
                }
                delay(controlIntervalMs)
            }
        }
    }

    fun stopNavigation() {
        controlJob?.cancel()
        controlJob = null
        scrollTick = 0
        lastInstructionKey = null
        _hud.value = HudState(active = false)
    }

    fun onInstruction(step: NavInstruction?) {
        _hud.value = _hud.value.copy(instruction = step)
    }

    private fun jupiterCleanLine(step: NavInstruction, maxChars: Int, scrollTick: Int): String {
        val raw = step.street.ifBlank { step.title }.trim()
        if (raw.length <= maxChars) return raw
        val padded = "$raw   "
        val offset = (scrollTick * 3) % padded.length
        return (padded + padded).substring(offset, offset + maxChars).trimEnd()
    }

    private fun jupiterPictogramName(picId: Int?, title: String): String {
        val t = title.lowercase()
        return when {
            t.contains("roundabout") -> "ROUNDABOUT"
            t.contains("u-turn") || t.contains("uturn") -> "U TURN"
            t.contains("sharp left") -> "SHARP LEFT"
            t.contains("slight left") || t.contains("keep left") -> "SLIGHT LEFT"
            t.contains("left") -> "TURN LEFT"
            t.contains("sharp right") -> "SHARP RIGHT"
            t.contains("slight right") || t.contains("keep right") -> "SLIGHT RIGHT"
            t.contains("right") -> "TURN RIGHT"
            t.contains("destination") || t.contains("arrive") -> "ARRIVED"
            picId == Maneuvers.SHARP_LEFT -> "SHARP LEFT"
            picId == Maneuvers.SLIGHT_LEFT || picId == Maneuvers.SLIGHT_LEFT_ALT -> "SLIGHT LEFT"
            picId == Maneuvers.SHARP_RIGHT -> "SHARP RIGHT"
            picId == Maneuvers.SLIGHT_RIGHT || picId == Maneuvers.SLIGHT_RIGHT_ALT -> "SLIGHT RIGHT"
            picId == Maneuvers.U_TURN_LEFT || picId == Maneuvers.U_TURN_RIGHT -> "U TURN"
            picId == Maneuvers.ARRIVED -> "ARRIVED"
            picId in Maneuvers.ROUNDABOUT_SLIGHT_LEFT..Maneuvers.ROUNDABOUT_U_TURN -> "ROUNDABOUT"
            else -> "GO STRAIGHT"
        }
    }

    private fun NavInstruction.toNavStep() = NavStep(
        distanceToTurnMeters = distanceMeters.coerceIn(0, 0xFFFF),
        etaMinutes = etaMinutes,
        remainingDistanceMeters = 0,
        pictogram = pictogram ?: Pictograms.STRAIGHT,
        streetName = street,
    )

    companion object {
        private const val TAG = "NavBridge"
    }
}
