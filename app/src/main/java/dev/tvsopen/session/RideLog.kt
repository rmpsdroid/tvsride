package dev.tvsopen.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

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
                distanceKm = o.optDouble("d", 0.0),
            )
        }
    }.getOrDefault(emptyList())

    private fun persist(list: List<Ride>) {
        val arr = JSONArray()
        list.take(MAX_RIDES).forEach { r ->
            arr.put(
                JSONObject()
                    .put("s", r.startedAt)
                    .put("e", r.endedAt ?: JSONObject.NULL)
                    .put("d", r.distanceKm),
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
        _rides.value = list.take(MAX_RIDES)
    }

    fun start(now: Long = System.currentTimeMillis()) {
        var current = _rides.value
        val open = current.firstOrNull()
        if (open?.inProgress == true) {
            if (now - open.startedAt < STALE_OPEN_RIDE_MS) return
            current = if (open.distanceKm >= MIN_RIDE_DISTANCE_KM) {
                listOf(open.copy(endedAt = now)) + current.drop(1)
            } else {
                current.drop(1)
            }
            persist(current)
        }
        persist(listOf(Ride(now, null, 0.0)) + _rides.value)
    }

    fun update(distanceKm: Double) {
        val current = _rides.value
        val open = current.firstOrNull() ?: return
        if (!open.inProgress || distanceKm < 0) return
        if ((distanceKm * 10).toLong() == (open.distanceKm * 10).toLong()) return
        persist(listOf(open.copy(distanceKm = distanceKm)) + current.drop(1))
    }

    /** Closes the open ride and folds it into the running totals, dropping < 0.1 km ghost reconnects. */
    fun finish(now: Long = System.currentTimeMillis()) {
        val current = _rides.value
        val open = current.firstOrNull() ?: return
        if (!open.inProgress) return
        if (open.distanceKm < MIN_RIDE_DISTANCE_KM) {
            persist(current.drop(1))
            return
        }
        sp.edit()
            .putInt(KEY_TOTAL, totalRides + 1)
            .putString(KEY_DIST, (totalDistanceKm + open.distanceKm).toString())
            .apply()
        persist(listOf(open.copy(endedAt = now)) + current.drop(1))
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
