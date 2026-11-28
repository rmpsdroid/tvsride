package dev.tvsopen.ui

import java.util.Locale

import java.util.Date

import java.text.SimpleDateFormat

import dev.tvsopen.transport.label

import dev.tvsopen.session.Telemetry

import dev.tvsopen.session.ConnectionState1

import dev.tvsopen.protocol.DriverRegistry

import androidx.compose.ui.text.style.TextAlign

import androidx.core.content.ContextCompat

import android.content.pm.PackageManager

import android.Manifest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tvsopen.log.Level
import dev.tvsopen.log.Log
import dev.tvsopen.log.LogRecord

/**
 * Live log viewer.
 *
 * This is a debugging surface, not decoration. When the app is pointed at a
 * real scooter, this screen — and the exported file behind it — is the evidence
 * that every protocol fix was based on something observed rather than assumed.
 */
@Composable
fun LogScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    connection: ConnectionState1 = ConnectionState1(),
    telemetry: Telemetry = Telemetry(),
    hasBluetoothPermission: Boolean = false,
    hasNotificationAccess: Boolean = false,
) {

    val ctx = LocalContext.current
    val records by Log.records.collectAsStateWithLifecycle()
    val frameCount by Log.frameCount.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val driver = remember(connection.model) { DriverRegistry.forModel(connection.model) }
    val callPermissionsGranted = remember(ctx) {
        listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
        ).all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    val lastConnectedRecord = remember(records) {
        records.lastOrNull {
            it.tag == "RideService" &&
                (it.message == "state -> CONNECTED" ||
                    (it.message.contains("reconnect attempt #") && it.message.endsWith("connected")))
        }
    }
    val reconnectAttempts = remember(records) {
        records.asSequence()
            .filter { it.tag == "RideService" && it.message.contains("reconnect #") }
            .mapNotNull { Regex("reconnect #(\\d+)").find(it.message)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .distinct()
            .count()
    }
    val lastFrameAgeMs = telemetry.lastFrameAt.takeIf { it > 0L }
        ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
    val lastFrameLabel = when {
        lastFrameAgeMs == null -> "No telemetry received"
        lastFrameAgeMs < 3_000L -> "Live"
        else -> "${lastFrameAgeMs / 1_000}s ago"
    }
    val lastConnectedLabel = lastConnectedRecord?.let {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.atMillis))
    } ?: "No successful link in this session"
    val reconnectLabel = if (reconnectAttempts == 0) "None recorded"
        else "$reconnectAttempts attempt(s) logged"
    val clockSyncLabel = if (driver.capabilities.timeSync && driver.capabilities.keepalive)
        "Included in keepalive · hardware check pending"
    else "Not supported by this model"
    // Default to INFO, not DEBUG. Raw hex frames are DEBUG records, and a live
    // link produces one per channel per second at best; opening the tab on
    // DEBUG buried the events that explain a session (service created,
    // connected, subscribed, decoded) under a wall of telemetry. The frames are
    // one tap away and the caption below says so.
    var minLevel by remember { mutableStateOf(Level.INFO) }
    var autoScroll by remember { mutableStateOf(true) }

    val visible = remember(records, minLevel) {
        records.filter { it.level >= minLevel }
    }

    // Key on the tail record, not visible.size: snapshot() caps the list at
    // 500, so once full the size never changes and Follow silently stops.
    LaunchedEffect(visible.lastOrNull(), autoScroll) {
        if (autoScroll && visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }

    Column(modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Advanced diagnostics", style = MaterialTheme.typography.titleLarge)
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Build ${dev.tvsopen.BuildConfig.BUILD_VERSION_LABEL} · ${frameCount} frames · ${visible.size} shown" +
                        if (minLevel > Level.DEBUG) " · tap DEBUG for raw frames" else "",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "Shared exports omit raw BLE payloads to protect caller and message data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR).forEach { lvl ->
                        FilterChip(
                            selected = minLevel == lvl,
                            onClick = { minLevel = lvl },
                            label = { Text(lvl.name, fontSize = 11.sp) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { autoScroll = !autoScroll }) {
                        Text(if (autoScroll) "Follow: on" else "Follow: off")
                    }
                    TextButton(onClick = { Log.clear() }) { Text("Clear") }
                    OutlinedButton(onClick = {
                        val out = Log.export(Log.exportDir(ctx))
                        if (out == null) {
                            Toast.makeText(ctx, "No log file yet", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                ctx,
                                "Saved redacted diagnostics to ${out.absolutePath}",
                                Toast.LENGTH_LONG,
                            ).show()
                            Log.i(TAG, "exported logs to ${out.absolutePath}")
                        }
                    }) { Text("Export redacted") }
                }
            }
        }

        DiagnosticsStatusCard(
            modelName = connection.model.displayName,
            connectionState = connection.state.label,
            lastConnected = lastConnectedLabel,
            lastFrame = lastFrameLabel,
            reconnectAttempts = reconnectLabel,
            bluetoothPermission = hasBluetoothPermission,
            notificationAccess = hasNotificationAccess,
            callPermissions = callPermissionsGranted,
            clockSync = clockSyncLabel,
        )

        HorizontalDivider()

        if (visible.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null)
                Text(
                    "No records yet. Connect to a scooter, or check the filter level.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(visible) { r -> LogRow(r) }
            }
        }
    }
}

@Composable
private fun DiagnosticsStatusCard(
    modelName: String,
    connectionState: String,
    lastConnected: String,
    lastFrame: String,
    reconnectAttempts: String,
    bluetoothPermission: Boolean,
    notificationAccess: Boolean,
    callPermissions: Boolean,
    clockSync: String,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connection health", style = MaterialTheme.typography.titleMedium)
            Text(modelName, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            DiagnosticStatusRow("Bluetooth", connectionState)
            DiagnosticStatusRow("Last successful link", lastConnected)
            DiagnosticStatusRow("Last telemetry frame", lastFrame)
            DiagnosticStatusRow("Reconnect attempts", reconnectAttempts)
            HorizontalDivider()
            DiagnosticStatusRow("Bluetooth permission", if (bluetoothPermission) "Granted" else "Missing")
            DiagnosticStatusRow("Notification access", if (notificationAccess) "Granted" else "Missing")
            DiagnosticStatusRow("Phone / call permissions", if (callPermissions) "Granted" else "Incomplete")
            DiagnosticStatusRow("Clock sync capability", clockSync)
        }
    }
}

@Composable
private fun DiagnosticStatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            Modifier.weight(1.1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

private const val TAG = "LogScreen"

@Composable
private fun LogRow(r: LogRecord) {
    val colour = when (r.level) {
        Level.ERROR -> MaterialTheme.colorScheme.error
        Level.WARN -> MaterialTheme.colorScheme.tertiary
        Level.INFO -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // BLE frames get their own colour: they are what you actually read.
    val isFrame = r.tag == "BLE"
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = if (isFrame) r.message else r.format(),
            color = if (isFrame) MaterialTheme.colorScheme.onSurface else colour,
            fontFamily = FontFamily.Monospace,
            fontSize = if (isFrame) 12.sp else 11.sp,
            fontWeight = if (isFrame) FontWeight.Medium else FontWeight.Normal,
        )
        r.throwable?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}
