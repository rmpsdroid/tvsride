package dev.tvsopen.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import dev.tvsopen.log.Log
import dev.tvsopen.protocol.Gatt
import dev.tvsopen.protocol.GattConfig
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

val ConnectionState.label: String
    get() = when (this) {
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.SCANNING -> "Scanning"
        ConnectionState.CONNECTING -> "Connecting"
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.RECONNECTING -> "Reconnecting"
    }

data class BleFrame(val direction: Direction, val bytes: ByteArray, val atMillis: Long) {
    enum class Direction { INBOUND, OUTBOUND }
    val hex: String get() = bytes.joinToString(" ") { "%02X".format(it) }
    override fun equals(other: Any?) =
        this === other || (other is BleFrame && direction == other.direction &&
            bytes.contentEquals(other.bytes) && atMillis == other.atMillis)
    override fun hashCode() = ((direction.ordinal * 31 + bytes.contentHashCode()) * 31 + atMillis.hashCode())
}

class ScooterBleService(private val context: Context) {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<BleFrame>(replay = 0, extraBufferCapacity = 256)
    val frames: SharedFlow<BleFrame> = _frames.asSharedFlow()

    private val _lastFrameAt = MutableStateFlow(0L)
    val lastFrameAt: StateFlow<Long> = _lastFrameAt.asStateFlow()

    private val writeLock = Mutex()
    @Volatile
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var discoveryStarted = false

    private var dropWarned = false
    private var lastWriteFinishedAt = 0L
    @Volatile private var activeScanner: BluetoothLeScanner? = null
    @Volatile private var activeScanCallback: ScanCallback? = null

    val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun isSupported(): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.bluetooth_le")

    @SuppressLint("MissingPermission")
    fun connect(address: String, config: GattConfig = GattConfig.DEFAULT): Boolean {
        if (gatt != null) return false
        val a = adapter ?: return false
        val device: BluetoothDevice = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad address $address: ${e.message}")
            return false
        }
        _state.value = ConnectionState.CONNECTING

        val scanner: BluetoothLeScanner? = if (!config.autoConnect) a.bluetoothLeScanner else null
        lateinit var openGatt: () -> Unit
        val scanCallback = if (scanner != null) {
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    if (result?.device?.address.equals(address, ignoreCase = true)) {
                        Log.i(TAG, "saved scooter advertisement found")
                        runCatching { scanner.stopScan(this) }
                        mainHandler.post { openGatt() }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "pre-connect scan failed: $errorCode; falling back to direct GATT")
                    mainHandler.post { openGatt() }
                }
            }
        } else {
            null
        }

        openGatt = {
            if (scanCallback != null && scanner != null) {
                runCatching { scanner.stopScan(scanCallback) }
            }
            if (gatt == null && _state.value == ConnectionState.CONNECTING) {
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(
                        context,
                        config.autoConnect,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    device.connectGatt(context, config.autoConnect, gattCallback)
                }
            }
        }

        if (scanner != null && scanCallback != null && a.isEnabled) {
            val startedScan = runCatching {
                scanner.startScan(null, android.bluetooth.le.ScanSettings.Builder().build(), scanCallback)
                true
            }.getOrDefault(false)
            if (startedScan) {
                activeScanner = scanner
                activeScanCallback = scanCallback
                mainHandler.postDelayed(openGatt, PRE_CONNECT_SCAN_MS)
                return true
            }
        }

        openGatt()
        return gatt != null
    }

    @SuppressLint("MissingPermission")
    suspend fun disconnect() {
        mainHandler.removeCallbacksAndMessages(null)
        val scan = activeScanner
        val callback = activeScanCallback
        if (scan != null && callback != null) {
            runCatching { scan.stopScan(callback) }
            activeScanner = null
            activeScanCallback = null
        }
        writeLock.withLock {
            gatt?.let { closeGatt(it) }
            writeChar = null
        }
        _state.value = ConnectionState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(g: BluetoothGatt) {
        if (gatt !== g) {
            runCatching { g.close() }
                .onFailure { Log.w(TAG, "gatt close: ${it.message}") }
            return
        }
        writeChar = null
        mainHandler.removeCallbacksAndMessages(null)
        activeScanner?.let { scanner ->
            activeScanCallback?.let { callback -> runCatching { scanner.stopScan(callback) } }
        }
        activeScanner = null
        activeScanCallback = null
        gatt = null
        discoveryStarted = false
        runCatching { g.close() }
            .onFailure { Log.w(TAG, "gatt close: ${it.message}") }
    }

    suspend fun write(bytes: ByteArray): Boolean = writeLock.withLock {
        if (_state.value == ConnectionState.CONNECTED && writeChar == null) {
            val deadline = SystemClock.elapsedRealtime() + WRITE_READY_WAIT_MS
            while (writeChar == null && gatt != null &&
                _state.value == ConnectionState.CONNECTED &&
                SystemClock.elapsedRealtime() < deadline) {
                delay(50)
            }
        }
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null || _state.value != ConnectionState.CONNECTED) {
            if (!dropWarned) {
                dropWarned = true
                val why = when {
                    g == null -> "no gatt"
                    ch == null -> "no write characteristic"
                    else -> "state is ${_state.value}"
                }
                Log.w(TAG, "link not ready, dropping writes ($why)")
            }
            return@withLock false
        }

        val sinceLast = SystemClock.elapsedRealtime() - lastWriteFinishedAt
        if (sinceLast in 0 until INTER_WRITE_DELAY_MS) {
            delay(INTER_WRITE_DELAY_MS - sinceLast)
        }

        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            val result = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = bytes
                    @Suppress("DEPRECATION")
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "write refused: ${e.message}")
                false
            }
            cont.resumeWith(Result.success(result))
        }
        lastWriteFinishedAt = SystemClock.elapsedRealtime()
        if (ok) {
            dropWarned = false
            emit(BleFrame(BleFrame.Direction.OUTBOUND, bytes, System.currentTimeMillis()))
        }
        ok
    }

    private fun emit(frame: BleFrame) {
        _lastFrameAt.value = frame.atMillis
        _frames.tryEmit(frame)
        Log.frame(
            if (frame.direction == BleFrame.Direction.INBOUND) "IN " else "OUT",
            frame.bytes,
            frame.atMillis,
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) {
                Log.d(TAG, "ignoring connection callback from a stale GATT client")
                runCatching { g.close() }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected (status=$status); negotiating MTU")
                    _state.value = ConnectionState.CONNECTING
                    try {
                        if (g.requestMtu(Gatt.REQUESTED_MTU)) {
                            mainHandler.postDelayed(
                                { startDiscovery(g) },
                                DISCOVERY_FALLBACK_MS,
                            )
                        } else {
                            startDiscovery(g)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "post-connect setup refused: ${e.message}")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    _state.value = ConnectionState.DISCONNECTED
                    closeGatt(g)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "service discovery failed: status=$status")
                _state.value = ConnectionState.DISCONNECTED
                closeGatt(g)
                return
            }
            val svc = resolveVerifiedService(g)
            if (svc == null) {
                Log.w(TAG, "no verified SmartXonnect service on ${g.device.address}")
                _state.value = ConnectionState.DISCONNECTED
                closeGatt(g)
                return
            }
            val ch = svc.getCharacteristic(UUID.fromString(Gatt.WRITE))
            if (ch == null) {
                Log.w(TAG, "write characteristic ${Gatt.WRITE} missing")
                _state.value = ConnectionState.DISCONNECTED
                closeGatt(g)
                return
            }
            writeChar = ch
            Log.i(
                TAG,
                "service ready (${svc.uuid}), write char props=0x${ch.properties.toString(16)}",
            )
            enableNotifications(g, svc)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (g !== gatt) return
            emit(BleFrame(BleFrame.Direction.INBOUND, value, System.currentTimeMillis()))
        }

        @Deprecated("Superseded by the ByteArray overload on API 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (g !== gatt) return
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: return
            emit(BleFrame(BleFrame.Direction.INBOUND, v, System.currentTimeMillis()))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt) return
            Log.i(TAG, "MTU=$mtu status=$status")
            startDiscovery(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (g !== gatt) {
                Log.d(TAG, "ignoring descriptor write from a closed client")
                return
            }
            Log.d(TAG, "descriptor write status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "notification subscription failed: status=$status")
                _state.value = ConnectionState.DISCONNECTED
                closeGatt(g)
                return
            }
            writeNextCccd(g)
        }
    }

    private val cccdQueue = ArrayDeque<BluetoothGattDescriptor>()
    private val cccdLock = Any()

    @SuppressLint("MissingPermission")
    private fun writeNextCccd(g: BluetoothGatt) {
        val next = synchronized(cccdLock) {
            if (cccdQueue.isNotEmpty()) cccdQueue.removeFirst() else null
        } ?: run {
            if (g === gatt && writeChar != null) {
                dropWarned = false
                _state.value = ConnectionState.CONNECTED
                Log.i(TAG, "GATT ready: service, write characteristic and notifications configured")
            }
            return
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                g.writeDescriptor(next, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothGatt.GATT_SUCCESS
            } catch (e: SecurityException) {
                Log.e(TAG, "CCCD write refused: ${e.message}")
                false
            }
        } else {
            @Suppress("DEPRECATION")
            next.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            try {
                @Suppress("DEPRECATION")
                g.writeDescriptor(next)
            } catch (e: SecurityException) {
                Log.e(TAG, "CCCD write refused: ${e.message}")
                false
            }
        }
        if (!ok) {
            Log.w(TAG, "CCCD write refused; continuing with the rest")
            writeNextCccd(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(g: BluetoothGatt): Boolean {
        val ok = try {
            g.discoverServices()
        } catch (e: SecurityException) {
            Log.e(TAG, "discoverServices refused: ${e.message}")
            return false
        }
        if (!ok) Log.w(TAG, "discoverServices refused")
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery(g: BluetoothGatt) {
        if (g !== gatt || discoveryStarted) return
        discoveryStarted = true
        mainHandler.removeCallbacksAndMessages(null)
        if (!discoverServices(g)) {
            discoveryStarted = false
            _state.value = ConnectionState.DISCONNECTED
            closeGatt(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveVerifiedService(g: BluetoothGatt): android.bluetooth.BluetoothGattService? {
        val serviceUuid = UUID.fromString(Gatt.SERVICE)
        val writeUuid = UUID.fromString(Gatt.WRITE)
        val readUuid = UUID.fromString(Gatt.READ)

        val exact = g.getService(serviceUuid)
        if (exact != null) {
            val hasBoth = exact.getCharacteristic(writeUuid) != null &&
                exact.getCharacteristic(readUuid) != null
            if (hasBoth) return exact
            Log.e(
                TAG,
                "service $serviceUuid present but a characteristic is missing; " +
                    "refusing the link (hard failure, no fallback)",
            )
            return null
        }

        val byCharacteristics = g.services?.firstOrNull {
            it.getCharacteristic(writeUuid) != null && it.getCharacteristic(readUuid) != null
        }
        if (byCharacteristics == null) {
            Log.e(TAG, "no service exposes both $writeUuid and $readUuid")
        } else {
            Log.w(
                TAG,
                "service UUID $serviceUuid absent; resolved by characteristics on ${byCharacteristics.uuid}",
            )
        }
        return byCharacteristics
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, svc: android.bluetooth.BluetoothGattService) {
        val pending = ArrayList<BluetoothGattDescriptor>()
        for (uuid in listOf(Gatt.READ, Gatt.DEVICE_INFO)) {
            val ch = svc.getCharacteristic(UUID.fromString(uuid)) ?: continue
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD)
            val canNotify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            if (cccd != null && canNotify) pending += cccd
            else Log.d(TAG, "no notifiable CCCD for $uuid")
        }
        if (pending.isEmpty()) {
            _state.value = ConnectionState.CONNECTED
            Log.i(TAG, "GATT ready: no notification descriptors required")
            return
        }
        synchronized(cccdLock) {
            cccdQueue.clear()
            cccdQueue.addAll(pending)
        }
        writeNextCccd(g)
    }

    companion object {
        private const val TAG = "ScooterBle"
        private const val DISCOVERY_FALLBACK_MS = 2500L
        private const val WRITE_READY_WAIT_MS = 2_000L
        private const val INTER_WRITE_DELAY_MS = 120L
        private const val PRE_CONNECT_SCAN_MS = 1200L

        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun looksValid(bytes: ByteArray): Boolean {
            if (bytes.size < 3) return false
            val start = bytes[0].toInt() and 0xFF
            return start == 0x5A || start == 0x5B || start == 0x9A || start == 0x9B
        }

        fun parcelUuid(uuid: String): ParcelUuid = ParcelUuid(UUID.fromString(uuid))
    }
}
