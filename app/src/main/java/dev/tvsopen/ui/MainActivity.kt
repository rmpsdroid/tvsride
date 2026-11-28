package dev.tvsopen.ui

import android.Manifest
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings as SettingsNav
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tvsopen.Nucleus
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.session.Session
import dev.tvsopen.transport.ConnectionState
import dev.tvsopen.transport.RideService
import dev.tvsopen.transport.ScooterPairing
import dev.tvsopen.transport.ScooterScanner
import dev.tvsopen.ui.theme.ThemePrefs
import dev.tvsopen.ui.theme.TVSRideTheme
import java.util.Locale

/**
 * Single-activity host: bottom navigation between Ride, Trips and Settings.
 *
 * Deliberately thin. The protocol layer is the substance; the UI observes state
 * and sends intents to [RideService]. There is no account, no repository and no
 * network client, because there is no network client in the app at all.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val wasFirstRun = isFirstRun
            if (wasFirstRun) markFirstRunComplete()

            val denied = granted.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Log.i("MainActivity", "declined: ${denied.joinToString()}")
            }

            if (hasBlePermission()) {
                if (RidePrefs.load(this).lastAddress.isNullOrBlank()) openScanner()
                else connectToRememberedCluster()
            }
        }

    private val isFirstRun: Boolean
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FIRST_RUN, true)

    private fun markFirstRunComplete() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FIRST_RUN, false).apply()
    }

    private fun requestAllPermissionsOnFirstRun() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            missing += Manifest.permission.BLUETOOTH_SCAN
            missing += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        missing += Manifest.permission.READ_PHONE_STATE
        missing += Manifest.permission.READ_CONTACTS
        missing += Manifest.permission.READ_CALL_LOG

        val needed = missing.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            markFirstRunComplete()
            if (RidePrefs.load(this).lastAddress.isNullOrBlank()) openScanner()
            else connectToRememberedCluster()
            return
        }
        Log.i("MainActivity", "first run: requesting ${needed.size} permissions")
        permissionLauncher.launch(needed.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Nucleus.init()
        Log.enableFileSink(java.io.File(filesDir, "logs"))
        ThemePrefs.load(this)
        val ridePrefs = RidePrefs.load(this)
        Session.attachRideLog(applicationContext)
        Session.loadParked(applicationContext)
        Session.setRideMode(ridePrefs.rideMode)
        Log.i("App", "activity created; drivers registered; theme=${ThemePrefs.mode}")

        setContent {
            AppRoot(
                autoConnect = autoConnectState,
                onFindMyChange = { send(it) },
                onNavMirrorChange = { send(it) },
                onScan = { openScanner() },
                onDisconnect = { send(RideService.ACTION_DISCONNECT) },
                onOpenNotificationAccess = { openNotificationAccess() },
                onAutoConnectChange = { enabled ->
                    autoConnectState = enabled
                    val prefs = RidePrefs.load(this@MainActivity)
                    prefs.autoConnect = enabled
                    if (enabled) {
                        prefs.lastAddress?.let { setUpCompanion(it) }
                    } else {
                        pendingCompanionAddress = null
                        stopObservingCompanion()
                    }
                },
                hasNotificationAccess = notificationAccess,
            )
        }
    }

    @Composable
    private fun AppRoot(
        autoConnect: Boolean,
        onFindMyChange: (String) -> Unit,
        onNavMirrorChange: (String) -> Unit,
        onScan: () -> Unit,
        onDisconnect: () -> Unit,
        onOpenNotificationAccess: () -> Unit,
        onAutoConnectChange: (Boolean) -> Unit,
        hasNotificationAccess: Boolean,
    ) {
        var tab by rememberSaveable { mutableStateOf(0) }
        var showDiagnostics by rememberSaveable { mutableStateOf(false) }
        var findMy by rememberSaveable { mutableStateOf(false) }
        var navMirroring by rememberSaveable {
            mutableStateOf(RidePrefs.load(this@MainActivity).navigationMirroring)
        }

        val keepScreenOn = RidePrefs.load(this@MainActivity).keepScreenOn
        LifecycleResumeEffect(keepScreenOn) {
            if (keepScreenOn) {
                this@MainActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onPauseOrDispose {
                this@MainActivity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        val telemetry by Session.telemetry.collectAsStateWithLifecycle()
        val hud by Session.hud.collectAsStateWithLifecycle()
        val connection by Session.connection.collectAsStateWithLifecycle()

        LaunchedEffect(connection.state, navMirroring) {
            if (navMirroring && connection.state == ConnectionState.CONNECTED) {
                onNavMirrorChange(RideService.ACTION_START_NAV)
            } else if (!navMirroring && connection.state == ConnectionState.CONNECTED) {
                onNavMirrorChange(RideService.ACTION_STOP_NAV)
            }
        }
        val parked by Session.parked.collectAsStateWithLifecycle()
        val rideMode by Session.rideMode.collectAsStateWithLifecycle()

        TVSRideTheme(mode = ThemePrefs.mode, darkTheme = isSystemInDarkTheme()) {
            Scaffold(
                bottomBar = {
                    if (!scanning.value) NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0 && !showDiagnostics,
                            onClick = { tab = 0; showDiagnostics = false },
                            icon = { Icon(Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = "Ride") },
                            label = { Text("Ride") },
                        )
                        NavigationBarItem(
                            selected = tab == 1 && !showDiagnostics,
                            onClick = { tab = 1; showDiagnostics = false },
                            icon = { Icon(Icons.Filled.Route, contentDescription = "Trips") },
                            label = { Text("Trips") },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2; showDiagnostics = false },
                            icon = { Icon(Icons.Filled.SettingsNav, contentDescription = "Settings") },
                            label = { Text("Settings") },
                        )
                    }
                },
            ) { padding ->
                androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    if (scanning.value) {
                        ScanScreen(
                            scanner = scanner,
                            pairing = pairing,
                            lastModelId = RidePrefs.load(this@MainActivity).lastModelId,
                            onPick = { address, model ->
                                scanning.value = false
                                RidePrefs.load(this@MainActivity)
                                    .updateSelectedModel(address, model.name)
                                RidePrefs.load(this@MainActivity)
                                    .updateLastConnection(address, model.name)
                                connectTo(address, model)
                            },
                            onBack = { scanning.value = false },
                        )
                    } else if (showDiagnostics) {
                        LogScreen(
                            onBack = { showDiagnostics = false },
                            connection = connection,
                            telemetry = telemetry,
                            hasBluetoothPermission = hasBlePermission(),
                            hasNotificationAccess = hasNotificationAccess,
                        )
                    } else if (tab == 0) {
                        RideScreen(
                            telemetry = telemetry,
                            hud = hud,
                            connection = connection,
                            parked = parked,
                            rideMode = rideMode,
                            findMyEnabled = findMy,
                            navMirroring = navMirroring,
                            onFindMyChange = { on ->
                                findMy = on
                                onFindMyChange(
                                    if (on) RideService.ACTION_FIND_MY
                                    else RideService.ACTION_STOP_FIND_MY,
                                )
                            },
                            onNavMirrorChange = { on ->
                                navMirroring = on
                                RidePrefs.load(this@MainActivity).navigationMirroring = on
                                onNavMirrorChange(
                                    if (on) RideService.ACTION_START_NAV
                                    else RideService.ACTION_STOP_NAV,
                                )
                            },
                            onRideModeChange = { on ->
                                RidePrefs.load(this@MainActivity).updateRideMode(on)
                                Session.setRideMode(on)
                            },
                            onConnect = { openScanner() },
                            onOpenParked = { tab = 1 },
                        )
                    } else if (tab == 1) {
                        TripsScreen(parked = parked)
                    } else {
                        MoreScreen(
                            connection = connection,
                            riderName = RidePrefs.load(this@MainActivity).riderName,
                            onRiderNameChange = { name ->
                                RidePrefs.load(this@MainActivity).riderName = name
                                if (hasBlePermission()) {
                                    send(RideService.ACTION_UPDATE_RIDER_NAME)
                                }
                            },
                            keepScreenOn = keepScreenOn,
                            onKeepScreenOnChange = {
                                RidePrefs.load(this@MainActivity).updateKeepScreenOn(it)
                            },
                            autoConnect = autoConnect,
                            onAutoConnectChange = onAutoConnectChange,
                            notificationAccessGranted = hasNotificationAccess,
                            onOpenNotificationAccess = onOpenNotificationAccess,
                            onScan = onScan,
                            onDisconnect = onDisconnect,
                            navMirroring = navMirroring,
                            onNavMirrorChange = { on ->
                                navMirroring = on
                                RidePrefs.load(this@MainActivity).navigationMirroring = on
                                onNavMirrorChange(
                                    if (on) RideService.ACTION_START_NAV
                                    else RideService.ACTION_STOP_NAV,
                                )
                            },
                            rideMode = rideMode,
                            onRideModeChange = { on ->
                                RidePrefs.load(this@MainActivity).updateRideMode(on)
                                Session.setRideMode(on)
                            },
                            onSetBrightness = { level ->
                                sendWithExtras(RideService.ACTION_SET_BRIGHTNESS) {
                                    putExtra(RideService.EXTRA_VALUE, level)
                                }
                            },
                            onSetCalibration = { cmd, seconds ->
                                sendWithExtras(RideService.ACTION_SET_CALIBRATION) {
                                    putExtra(RideService.EXTRA_CAL_CMD, cmd)
                                    putExtra(RideService.EXTRA_VALUE, seconds)
                                }
                            },
                            onOpenDiagnostics = { showDiagnostics = true },
                        )
                    }
                }
            }
        }
    }

    private fun send(action: String) {
        if (!hasBlePermission()) {
            Log.w("MainActivity", "refusing to start $action: Bluetooth not granted")
            Toast.makeText(this, "Grant Bluetooth permission first", Toast.LENGTH_LONG).show()
            requestBlePermissions()
            return
        }
        runCatching { startForegroundService(Intent(this, RideService::class.java).setAction(action)) }
            .onFailure {
                Log.w("MainActivity", "could not start ride service: ${it.message}")
                Toast.makeText(this, "Ride service not running", Toast.LENGTH_LONG).show()
            }
    }

    private fun sendWithExtras(action: String, extras: Intent.() -> Unit) {
        if (!hasBlePermission()) {
            Log.w("MainActivity", "refusing to start $action: Bluetooth not granted")
            Toast.makeText(this, "Grant Bluetooth permission first", Toast.LENGTH_LONG).show()
            requestBlePermissions()
            return
        }
        val intent = Intent(this, RideService::class.java).setAction(action).apply(extras)
        runCatching { startForegroundService(intent) }
            .onFailure {
                Log.w("MainActivity", "could not start ride service: ${it.message}")
                Toast.makeText(this, "Ride service not running", Toast.LENGTH_LONG).show()
            }
    }

    private fun requestBlePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            connectToRememberedCluster()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun connectToRememberedCluster() {
        val prefs = RidePrefs.load(this)
        val address = prefs.lastAddress
        if (address.isNullOrBlank()) {
            Toast.makeText(this, "No scooter chosen yet — tap Connect to scan", Toast.LENGTH_LONG)
                .show()
            return
        }
        val model = prefs.lastModelId
            ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
            ?: VehicleModel.NTORQ
        connectTo(address, model)
    }

    private fun connectTo(address: String, model: VehicleModel) {
        val result = runCatching {
            startForegroundService(
                Intent(this, RideService::class.java)
                    .setAction(RideService.ACTION_CONNECT)
                    .putExtra(RideService.EXTRA_ADDRESS, address)
                    .putExtra(RideService.EXTRA_MODEL, model.name),
            )
        }
        result.onFailure {
            Toast.makeText(this, "Could not start the ride service", Toast.LENGTH_LONG).show()
        }
        if (result.isSuccess && RidePrefs.load(this).autoConnect) {
            setUpCompanion(address)
        }
    }

    private fun hasBlePermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private var notificationAccess by mutableStateOf(false)
    private var autoConnectState by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        notificationAccess = hasNotificationAccess()
        autoConnectState = RidePrefs.load(this).autoConnect
        if (isFirstRun && hasBlePermission()) {
            if (!autoConnectTried) {
                autoConnectTried = true
                requestAllPermissionsOnFirstRun()
            }
            return
        }
        if (!autoConnectTried) {
            autoConnectTried = true
            if (isFirstRun) {
                requestAllPermissionsOnFirstRun()
            } else {
                autoConnectOnLaunch()
            }
        }
    }

    private var autoConnectTried = false

    private var pendingCompanionAddress: String? = null

    private val companionLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val address = pendingCompanionAddress
            pendingCompanionAddress = null
            if (
                result.resultCode == RESULT_OK &&
                address != null &&
                RidePrefs.load(this).autoConnect
            ) {
                startObservingCompanion(address)
            } else {
                Log.w("MainActivity", "vehicle association was not completed or auto-connect was disabled")
            }
        }

    private val scanning = mutableStateOf(false)
    private val scanner: ScooterScanner by lazy { ScooterScanner(this) }
    private val pairing: ScooterPairing by lazy { ScooterPairing(this) }

    private fun openScanner() {
        if (!hasBlePermission()) {
            requestBlePermissions()
            return
        }
        scanner.start(RidePrefs.load(this).onlySmartXonnect)
        scanning.value = true
    }

    private fun autoConnectOnLaunch() {
        if (!hasBlePermission()) {
            Log.i("MainActivity", "auto-connect skipped: Bluetooth not granted")
            return
        }
        val prefs = RidePrefs.load(this)
        if (prefs.lastAddress.isNullOrBlank()) {
            Log.i("MainActivity", "auto-connect skipped: no scooter stored yet")
            return
        }
        val model = prefs.lastModelId
            ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
            ?: VehicleModel.NTORQ
        Log.i("MainActivity", "auto-connecting to ${model.displayName}")
        connectToRememberedCluster()
    }

    /** True when Android already associates this Bluetooth address with the app. */
    private fun isCompanionAssociated(address: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val manager = getSystemService(CompanionDeviceManager::class.java) ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            manager.associations.any { it.equals(address, ignoreCase = true) }
        }.getOrDefault(false)
    }

    private fun startObservingCompanion(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = getSystemService(CompanionDeviceManager::class.java) ?: return
        runCatching { manager.startObservingDevicePresence(address) }
            .onSuccess {
                RidePrefs.load(this).observedCompanion = address
                Log.i("MainActivity", "Android is watching for the vehicle")
            }
            .onFailure {
                Log.w("MainActivity", "could not watch for the vehicle: ${it.message}")
            }
    }

    private fun stopObservingCompanion() {
        val prefs = RidePrefs.load(this)
        val observed = prefs.observedCompanion ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                getSystemService(CompanionDeviceManager::class.java)
                    ?.stopObservingDevicePresence(observed)
            }.onFailure {
                Log.w("MainActivity", "could not stop vehicle presence watch: ${it.message}")
            }
        }
        prefs.observedCompanion = null
    }

    /**
     * Ask Android to associate the saved scooter so its presence can wake the
     * companion service. This is opt-in and only available on Android 12+.
     */
    private fun setUpCompanion(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val prefs = RidePrefs.load(this)
        if (!prefs.autoConnect) return
        if (!hasBlePermission()) {
            Log.w("MainActivity", "vehicle presence setup skipped: Bluetooth permission missing")
            return
        }

        val manager = getSystemService(CompanionDeviceManager::class.java)
        if (manager == null) {
            Log.w("MainActivity", "no Companion Device Manager on this phone")
            return
        }

        if (prefs.observedCompanion?.equals(address, ignoreCase = true) == false) {
            stopObservingCompanion()
        }
        if (isCompanionAssociated(address)) {
            startObservingCompanion(address)
            return
        }

        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                ScanFilter.Builder().setDeviceAddress(address.uppercase(Locale.ROOT)).build(),
            )
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()
        pendingCompanionAddress = address

        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                companionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            @Suppress("DEPRECATION")
            override fun onDeviceFound(intentSender: IntentSender) {
                companionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onFailure(error: CharSequence?) {
                pendingCompanionAddress = null
                Log.w("MainActivity", "vehicle association failed: $error")
            }
        }
        Log.i("MainActivity", "asking Android to associate the vehicle for auto-connect")
        runCatching { manager.associate(request, callback, null) }
            .onFailure {
                pendingCompanionAddress = null
                Log.w("MainActivity", "vehicle association refused: ${it.message}")
            }
    }

    private fun hasNotificationAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun openNotificationAccess() {
        val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching { startActivity(i) }
            .onFailure {
                Toast.makeText(this, "Open notification access from system settings", Toast.LENGTH_LONG).show()
            }
    }

    private companion object {
        const val PREFS = "tvsride"
        const val KEY_FIRST_RUN = "first_run_complete"
    }
}
