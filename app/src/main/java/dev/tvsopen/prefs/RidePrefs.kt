package dev.tvsopen.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class RidePrefs private constructor(private val ctx: Context) {

    private val sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var rideMode by mutableStateOf(sp.getBoolean(KEY_RIDE_MODE, false))
        private set

    var resumeOnBoot by mutableStateOf(sp.getBoolean(KEY_RESUME_BOOT, false))
        private set

    fun updateRideMode(on: Boolean) {
        rideMode = on
        sp.edit().putBoolean(KEY_RIDE_MODE, on).apply()
    }

    fun updateResumeOnBoot(on: Boolean) {
        resumeOnBoot = on
        sp.edit().putBoolean(KEY_RESUME_BOOT, on).apply()
    }

    val lastAddress: String?
        get() = sp.getString(KEY_LAST_ADDRESS, null)

    val lastModelId: String?
        get() = sp.getString(KEY_LAST_MODEL, null)

    fun updateLastConnection(address: String, modelId: String) {
        sp.edit()
            .putString(KEY_LAST_ADDRESS, address)
            .putString(KEY_LAST_MODEL, modelId)
            .apply()
    }

    fun updateSelectedModel(address: String, modelId: String) {
        sp.edit().putString(modelKey(address), modelId).apply()
    }

    fun selectedModel(address: String): String? = sp.getString(modelKey(address), null)

    var onlySmartXonnect: Boolean
        get() = sp.getBoolean(KEY_ONLY_SMART_XONNECT, false)
        set(value) { sp.edit().putBoolean(KEY_ONLY_SMART_XONNECT, value).apply() }

    var navigationMirroring: Boolean
        get() = sp.getBoolean(KEY_NAVIGATION_MIRRORING, true)
        set(value) { sp.edit().putBoolean(KEY_NAVIGATION_MIRRORING, value).apply() }

    var mirrorPhoneCalls: Boolean
        get() = sp.getBoolean(KEY_MIRROR_PHONE_CALLS, true)
        set(value) { sp.edit().putBoolean(KEY_MIRROR_PHONE_CALLS, value).apply() }

    var extraMapsPackages: Set<String>
        get() = sp.getStringSet(KEY_EXTRA_MAPS_PACKAGES, emptySet()) ?: emptySet()
        set(value) { sp.edit().putStringSet(KEY_EXTRA_MAPS_PACKAGES, value).apply() }

    var extraMessagePackages: Set<String>
        get() = sp.getStringSet(KEY_EXTRA_MESSAGE_PACKAGES, emptySet()) ?: emptySet()
        set(value) { sp.edit().putStringSet(KEY_EXTRA_MESSAGE_PACKAGES, value).apply() }

    var extraCallPackages: Set<String>
        get() = sp.getStringSet(KEY_EXTRA_CALL_PACKAGES, emptySet()) ?: emptySet()
        set(value) { sp.edit().putStringSet(KEY_EXTRA_CALL_PACKAGES, value).apply() }

    var mirrorAnyAppNotifications: Boolean
        get() = sp.getBoolean(KEY_MIRROR_ANY_APP_NOTIFICATIONS, false)
        set(value) { sp.edit().putBoolean(KEY_MIRROR_ANY_APP_NOTIFICATIONS, value).apply() }

    var keepScreenOn by mutableStateOf(sp.getBoolean(KEY_KEEP_SCREEN_ON, false))
        private set

    fun updateKeepScreenOn(on: Boolean) {
        keepScreenOn = on
        sp.edit().putBoolean(KEY_KEEP_SCREEN_ON, on).apply()
    }

    var mirrorMessages: Boolean
        get() = sp.getBoolean(KEY_MIRROR_MESSAGES, false)
        set(value) { sp.edit().putBoolean(KEY_MIRROR_MESSAGES, value).apply() }

    var riderName: String?
        get() = sp.getString(KEY_RIDER_NAME, null)
        set(value) {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
            sp.edit().apply {
                if (trimmed == null) remove(KEY_RIDER_NAME) else putString(KEY_RIDER_NAME, trimmed)
            }.apply()
        }

    var clusterBrightness: Int
        get() = sp.getInt(KEY_CLUSTER_BRIGHTNESS, 3)
        set(value) { sp.edit().putInt(KEY_CLUSTER_BRIGHTNESS, value).apply() }

    var followMeTimerSec: Int?
        get() = if (sp.contains(KEY_FOLLOW_ME_SEC)) sp.getInt(KEY_FOLLOW_ME_SEC, 0) else null
        set(value) {
            sp.edit().apply {
                if (value == null) remove(KEY_FOLLOW_ME_SEC) else putInt(KEY_FOLLOW_ME_SEC, value)
            }.apply()
        }

    var idleStopTimerSec: Int?
        get() = if (sp.contains(KEY_IDLE_STOP_SEC)) sp.getInt(KEY_IDLE_STOP_SEC, 10) else null
        set(value) {
            sp.edit().apply {
                if (value == null) remove(KEY_IDLE_STOP_SEC) else putInt(KEY_IDLE_STOP_SEC, value)
            }.apply()
        }

    var parkedAtMillis: Long
        get() = sp.getLong(KEY_PARKED_AT, 0L)
        private set(value) { sp.edit().putLong(KEY_PARKED_AT, value).apply() }

    var parkedLabel: String?
        get() = sp.getString(KEY_PARKED_LABEL, null)
        private set(value) { sp.edit().putString(KEY_PARKED_LABEL, value).apply() }

    fun saveParked(atMillis: Long, label: String?) {
        sp.edit()
            .putLong(KEY_PARKED_AT, atMillis)
            .putString(KEY_PARKED_LABEL, label)
            .apply()
    }

    companion object {
        private const val NAME = "tvsride"
        private const val KEY_RIDE_MODE = "ride_mode"
        private const val KEY_RESUME_BOOT = "resume_on_boot"
        private const val KEY_LAST_ADDRESS = "last_address"
        private const val KEY_LAST_MODEL = "last_model"
        private const val KEY_RIDER_NAME = "rider_name"
        private const val KEY_CLUSTER_BRIGHTNESS = "cluster_brightness"
        private const val KEY_FOLLOW_ME_SEC = "follow_me_sec"
        private const val KEY_IDLE_STOP_SEC = "idle_stop_sec"
        private const val KEY_ONLY_SMART_XONNECT = "only_smart_xonnect"
        private const val KEY_NAVIGATION_MIRRORING = "navigation_mirroring"
        private const val KEY_MIRROR_PHONE_CALLS = "mirror_phone_calls"
        private const val KEY_MIRROR_MESSAGES = "mirror_messages"
        private const val KEY_EXTRA_MAPS_PACKAGES = "extra_maps_packages"
        private const val KEY_EXTRA_MESSAGE_PACKAGES = "extra_message_packages"
        private const val KEY_EXTRA_CALL_PACKAGES = "extra_call_packages"
        private const val KEY_MIRROR_ANY_APP_NOTIFICATIONS = "mirror_any_app_notifications"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_PARKED_AT = "parked_at"
        private const val KEY_PARKED_LABEL = "parked_label"
        private const val KEY_SELECTED_MODEL_PREFIX = "selected_model_"

        private fun modelKey(address: String) = "$KEY_SELECTED_MODEL_PREFIX$address"

        @Volatile
        private var instance: RidePrefs? = null

        fun load(context: Context): RidePrefs =
            instance ?: synchronized(this) {
                instance ?: RidePrefs(context.applicationContext)
                    .also { instance = it }
            }
    }
}
