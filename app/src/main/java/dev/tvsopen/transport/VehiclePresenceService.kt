package dev.tvsopen.transport

import android.Manifest
import android.companion.CompanionDeviceService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs

/**
 * Receives Android companion-device presence events for the scooter the rider
 * explicitly associated with this app. The feature is opt-in in More settings.
 */
class VehiclePresenceService : CompanionDeviceService() {

    @Suppress("DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        val prefs = RidePrefs.load(this)
        if (!prefs.autoConnect) {
            Log.i(TAG, "vehicle appeared; auto-connect is off")
            return
        }

        val saved = prefs.lastAddress
        if (saved.isNullOrBlank() || !saved.equals(address, ignoreCase = true)) {
            Log.i(TAG, "a different companion device appeared; ignoring")
            return
        }

        if (!hasBlePermission()) {
            Log.w(TAG, "vehicle appeared but Bluetooth permission is missing")
            return
        }

        val model = prefs.selectedModel(saved) ?: prefs.lastModelId ?: "NTORQ"
        Log.i(TAG, "associated vehicle appeared; requesting connection")
        runCatching {
            startForegroundService(
                Intent(this, RideService::class.java)
                    .setAction(RideService.ACTION_CONNECT)
                    .putExtra(RideService.EXTRA_ADDRESS, saved)
                    .putExtra(RideService.EXTRA_MODEL, model),
            )
        }.onFailure {
            Log.w(TAG, "could not start ride service: ${it.message}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        // Do not disconnect here: the link can survive a brief range drop.
        // RideService owns reconnection when the GATT connection actually drops.
        Log.i(TAG, "vehicle moved out of range")
    }

    private fun hasBlePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "VehiclePresence"
    }
}
