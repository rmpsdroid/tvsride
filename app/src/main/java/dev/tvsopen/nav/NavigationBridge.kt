package dev.tvsopen.nav

import android.util.Log
import dev.tvsopen.protocol.DashState
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
        var showingMetrics = false
        var lastTwoRows = false

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
                    }
                    val nav = step.toNavStep()
                    val maneuver = step.displayLine(firstTextRowChars, scrollTick)
                    val fullMetrics = step.metricsLine(secondTextRowChars, scrollTick)

                    val metrics = fullMetrics
                    val twoRows = driver.buildNavTextSecondary(nav.copy(streetName = maneuver)) != null &&
                        maneuver.isNotBlank() && metrics.isNotBlank()

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

                    // Jupiter clusters use 0x5B 0x50 for the pictogram icon by name.
                    if (isJupiter) {
                        val picName = jupiterPictogramName(step.pictogram, step.title)
                        driver.buildPictogram(picName)?.let { ble.write(it) }
                    }

                    if (twoRows) {
                        driver.buildNavText(nav.copy(streetName = maneuver))?.let { ble.write(it) }
                        driver.buildNavTextSecondary(
                            nav.copy(streetName = metrics),
                        )?.let { ble.write(it) }
                    } else {
                        val line = if (showingMetrics) metrics else maneuver
                        driver.buildNavText(nav.copy(streetName = line))?.let { ble.write(it) }
                        showingMetrics = !showingMetrics
                    }
                    lastTwoRows = twoRows
                    scrollTick++
                }
                delay(if (lastTwoRows || showingMetrics) controlIntervalMs else textSwapIntervalMs)
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
            picId == Pictograms.TURN_LEFT -> "TURN LEFT"
            picId == Pictograms.TURN_RIGHT -> "TURN RIGHT"
            picId == Pictograms.SHARP_LEFT -> "SHARP LEFT"
            picId == Pictograms.SHARP_RIGHT -> "SHARP RIGHT"
            picId == Pictograms.SLIGHT_LEFT -> "SLIGHT LEFT"
            picId == Pictograms.SLIGHT_RIGHT -> "SLIGHT RIGHT"
            picId == Pictograms.U_TURN -> "U TURN"
            picId == Pictograms.ARRIVED -> "ARRIVED"
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
