package dev.tvsopen.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.tvsopen.log.Log

/**
 * Observes SIM call state while the ride foreground service is alive.
 *
 * It deliberately does not request or read the caller's phone number. Caller
 * details, when Android exposes them, are enriched by the notification listener.
 */
class TelephonyCallMonitor(
    context: Context,
    private val onCallState: (Int) -> Unit,
) {
    private val appContext = context.applicationContext

    private var telephonyManager: TelephonyManager? = null
    private var modernCallback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null

    @Volatile
    private var registered = false

    fun start(): Boolean {
        if (registered) return true
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_PHONE_STATE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "SIM call monitoring unavailable: READ_PHONE_STATE not granted")
            return false
        }

        val manager = appContext.getSystemService(TelephonyManager::class.java)
        if (manager == null) {
            Log.w(TAG, "SIM call monitoring unavailable: TelephonyManager missing")
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        report(state)
                    }
                }
                manager.registerTelephonyCallback(
                    ContextCompat.getMainExecutor(appContext),
                    callback,
                )
                modernCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in API 31; retained for API 26–30")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        report(state)
                    }
                }
                @Suppress("DEPRECATION")
                manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = listener
            }
            telephonyManager = manager
            registered = true
            Log.i(TAG, "SIM call-state monitor registered (api=${Build.VERSION.SDK_INT})")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SIM call-state registration denied: ${e.message}")
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "SIM call-state registration failed: ${e.message}")
            false
        }
    }

    fun stop() {
        val manager = telephonyManager
        if (!registered || manager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallback?.let(manager::unregisterTelephonyCallback)
            } else {
                legacyListener?.let {
                    @Suppress("DEPRECATION")
                    manager.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SIM call-state unregister denied: ${e.message}")
        } finally {
            modernCallback = null
            legacyListener = null
            telephonyManager = null
            registered = false
            Log.i(TAG, "SIM call-state monitor stopped")
        }
    }

    private fun report(state: Int) {
        val label = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            else -> "UNKNOWN($state)"
        }
        Log.i(TAG, "SIM call state: $label")
        onCallState(state)
    }

    companion object {
        private const val TAG = "TelephonyCallMonitor"
    }
}
