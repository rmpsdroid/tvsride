package dev.tvsopen.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** One ride: a connected period, open or closed. */
data class Ride(
    val startedAt: Long,
    val endedAt: Long?,
    val distanceKm: Double,
) {
    val inProgress: Boolean get() = endedAt == null

    fun durationMinutes(now: Long = System.currentTimeMillis()): Long {
        val end = endedAt ?: now
        return ((end - startedAt).coerceAtLeast(0L)) / 60_000L
    }
}

/**
 * Local ride history. The ride list and lifetime totals are committed in one
 * SharedPreferences transaction so a process death cannot update only half
 * of the history.
 */
class RideLog(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("tvsride", Context.MODE_PRIVATE)
    private val _rides = MutableStateFlow(load())
    val rides: StateFlow<List<Ride>> = _rides.asStateFlow()

    val totalRides: Int get() = sp.getInt(KEY_TOTAL, 0)
    val totalDistanceKm: Double
        get() = sp.getString(KEY_DIST, null)?.toDoubleOrNull() ?: 0.0

    private fun load(): List<Ride> = runCatching {
        val arr = JSONArray(sp.getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Ride(
                startedAt = o.optLong("s"),
                endedAt = if (o.isNull("e")) null else o.optLong("e"),
                distanceKm = o.optDouble("d", 0.0).coerceAtLeast(0.0),
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(list: List<Ride>): String {
        val arr = JSONArray()
        list.take(MAX_RIDES).forEach { r ->
            arr.put(
                JSONObject()
                    .put("s", r.startedAt)
                    .put("e", r.endedAt ?: JSONObject.NULL)
                    .put("d", r.distanceKm),
            )
        }
        return arr.toString()
    }

    @Synchronized
    private fun persist(list: List<Ride>) {
        val bounded = list.take(MAX_RIDES)
        sp.edit().putString(KEY, encode(bounded)).commit()
        _rides.value = bounded
    }

    @Synchronized
    fun start(now: Long = System.currentTimeMillis()) {
        val current = _rides.value
        val open = current.firstOrNull()?.takeIf { it.inProgress }
        if (open != null) {
            // Never silently replace a live ride. A stale ride is finalized
            // through the same totals transaction before a new one is opened.
            if (now - open.startedAt < STALE_OPEN_RIDE_MS) return
            finish(now)
        }
        persist(listOf(Ride(now, null, 0.0)) + _rides.value)
        LogRide.event("started")
    }

    @Synchronized
    fun update(distanceKm: Double) {
        if (!distanceKm.isFinite() || distanceKm < 0.0) return
        val current = _rides.value
        val open = current.firstOrNull()?.takeIf { it.inProgress } ?: return
        val ticks = (distanceKm * 10.0).roundToInt().coerceAtLeast(0)
        val oldTicks = (open.distanceKm * 10.0).roundToInt().coerceAtLeast(0)
        if (ticks <= oldTicks) return
        val roundedKm = ticks / 10.0
        persist(listOf(open.copy(distanceKm = roundedKm)) + current.drop(1))
    }

    /** Finalizes an open ride and updates lifetime totals atomically. */
    @Synchronized
    fun finish(now: Long = System.currentTimeMillis()) {
        val current = _rides.value
        val open = current.firstOrNull()?.takeIf { it.inProgress } ?: return
        if (open.distanceKm < MIN_RIDE_DISTANCE_KM) {
            persist(current.drop(1))
            LogRide.event("discarded-short")
            return
        }

        val editor = sp.edit()
            .putString(KEY, encode(listOf(open.copy(endedAt = now)) + current.drop(1)))
            .putInt(KEY_TOTAL, totalRides + 1)
            .putString(KEY_DIST, (totalDistanceKm + open.distanceKm).toString())
        if (!editor.commit()) {
            LogRide.event("finish-persist-failed")
            return
        }
        _rides.value = (listOf(open.copy(endedAt = now)) + current.drop(1)).take(MAX_RIDES)
        LogRide.event("finished")
    }

    companion object {
        private const val KEY = "ride_log"
        private const val KEY_TOTAL = "ride_total_count"
        private const val KEY_DIST = "ride_total_distance"
        private const val MAX_RIDES = 100
        private const val MIN_RIDE_DISTANCE_KM = 0.1
        private const val STALE_OPEN_RIDE_MS = 6 * 60 * 60 * 1000L
    }
}

/** Kept deliberately local to avoid coupling persistence to the app logger. */
private object LogRide {
    fun event(name: String) {
        android.util.Log.i("RideLog", name)
    }
}
