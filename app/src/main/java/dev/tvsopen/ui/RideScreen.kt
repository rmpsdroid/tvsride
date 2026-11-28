package dev.tvsopen.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tvsopen.session.ConnectionState1
import dev.tvsopen.session.HudState
import dev.tvsopen.session.ParkedInfo
import dev.tvsopen.session.Telemetry
import dev.tvsopen.transport.ConnectionState
import dev.tvsopen.transport.label
import dev.tvsopen.ui.theme.AppSwitch
import dev.tvsopen.ui.theme.NumberStyle
import kotlinx.coroutines.delay

/**
 * Primary rider-facing screen. Keep the connection state and the few live
 * readings that matter most above the fold; put less frequently used telemetry
 * behind an explicit expansion.
 */
@Composable
fun RideScreen(
    telemetry: Telemetry,
    hud: HudState,
    connection: ConnectionState1,
    parked: ParkedInfo?,
    findMyEnabled: Boolean,
    navMirroring: Boolean,
    rideMode: Boolean,
    onFindMyChange: (Boolean) -> Unit,
    onNavMirrorChange: (Boolean) -> Unit,
    onRideModeChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onOpenParked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val connected = connection.state == ConnectionState.CONNECTED
    var showAllData by rememberSaveable { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }

    val ageMs = remember(telemetry.lastFrameAt, tick) {
        if (telemetry.lastFrameAt <= 0L) null
        else (System.currentTimeMillis() - telemetry.lastFrameAt).coerceAtLeast(0L)
    }
    val freshness = when {
        !connected -> "Connect to see live vehicle information"
        ageMs == null -> "Bluetooth connected · waiting for cluster data"
        ageMs < 3_000L -> "Live telemetry"
        ageMs < Telemetry.STALE_AFTER_MS -> "Updated ${ageMs / 1_000}s ago"
        else -> "Telemetry is stale · last update ${ageMs / 1_000}s ago"
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("TVSRide", style = MaterialTheme.typography.headlineLarge)
                Text(
                    connection.model.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ConnectionPill(connected = connected, label = connection.state.label)
        }

        Spacer(Modifier.height(16.dp))
        ConnectionCard(
            connected = connected,
            stateLabel = connection.state.label,
            freshness = freshness,
            onConnect = onConnect,
        )

        Spacer(Modifier.height(14.dp))
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    "CURRENT SPEED",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        telemetry.speedKmh?.toString() ?: "—",
                        style = NumberStyle.copy(fontSize = 58.sp, lineHeight = 62.sp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "km/h",
                        Modifier.padding(bottom = 9.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    if (telemetry.isFresh()) "Latest reading from your cluster"
                    else "Waiting for a fresh reading",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VehicleMetric(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.LocalGasStation,
                label = "Fuel",
                value = telemetry.fuelBars?.let { "$it / ${Telemetry.FUEL_BAR_MAX}" } ?: "—",
                unit = "bars",
            )
            VehicleMetric(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Speed,
                label = "Engine",
                value = telemetry.engineRpm?.toString() ?: "—",
                unit = "rpm",
            )
            VehicleMetric(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Place,
                label = "Range",
                value = telemetry.distanceToEmptyKm?.toString() ?: "—",
                unit = "km",
            )
        }

        if (hud.active) {
            Spacer(Modifier.height(18.dp))
            SectionHeading("Navigation")
            NavigationStatusCard(hud)
        }

        Spacer(Modifier.height(20.dp))
        SectionHeading("Quick controls")
        ToggleActionCard(
            icon = Icons.Filled.Navigation,
            title = "Navigation mirroring",
            subtitle = when {
                !navMirroring -> "Turn-by-turn instructions are off"
                hud.active -> "Showing directions from ${hud.provider ?: "your maps app"}"
                else -> "On · waiting for a navigation step"
            },
            checked = navMirroring,
            onCheckedChange = onNavMirrorChange,
        )
        Spacer(Modifier.height(10.dp))
        ToggleActionCard(
            icon = Icons.Filled.NotificationsActive,
            title = "Find My Scooter",
            subtitle = "Flash the lights and sound the horn",
            checked = findMyEnabled,
            onCheckedChange = onFindMyChange,
        )
        Spacer(Modifier.height(10.dp))
        ToggleActionCard(
            icon = Icons.Filled.Bluetooth,
            title = "Keep Ride Mode active",
            subtitle = if (rideMode) "Resume is enabled after a reboot" else "Resume is currently off",
            checked = rideMode,
            onCheckedChange = onRideModeChange,
        )
        Spacer(Modifier.height(10.dp))
        LastParkedAction(parked) {
            if (!openParkedLocation(context, parked)) onOpenParked()
        }

        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeading("Vehicle information", Modifier.weight(1f))
            TextButton(onClick = { showAllData = !showAllData }) {
                Text(if (showAllData) "Show less" else "Show all")
            }
        }
        if (showAllData) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VehicleMetric(
                    Modifier.weight(1f), Icons.Filled.Speed, "Top speed",
                    telemetry.topSpeedKmh?.toString() ?: "—", "km/h",
                )
                VehicleMetric(
                    Modifier.weight(1f), Icons.Filled.Place, "Odometer",
                    telemetry.odometerKm?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—",
                    "km",
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VehicleMetric(
                    Modifier.weight(1f), Icons.Filled.Route, "This connection",
                    telemetry.connectedDistanceKm()?.let {
                        String.format(java.util.Locale.US, "%.1f", it)
                    } ?: "—", "km",
                )
                VehicleMetric(
                    Modifier.weight(1f), Icons.Filled.BatteryChargingFull, "Battery",
                    telemetry.batteryVoltage?.let { String.format(java.util.Locale.US, "%.1f", it / 10.0) }
                        ?: "—", "V",
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VehicleMetric(
                    Modifier.weight(1f), Icons.Filled.Thermostat, "Coolant",
                    telemetry.engineTempC?.toString() ?: "—", "°C",
                )
                VehicleMetric(
                    Modifier.weight(1f), Icons.Filled.Speed, "Average economy",
                    telemetry.avgEconomyKmpl?.toString() ?: "—", "km/l",
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VehicleMetric(
                    Modifier.weight(1f), Icons.Filled.Build, "Service",
                    when (telemetry.serviceDue) {
                        true -> "Due"
                        false -> "OK"
                        null -> "—"
                    }, "",
                )
                VehicleMetric(
                    Modifier.weight(1f), Icons.Filled.Warning, "Check engine",
                    when (telemetry.milOn) {
                        true -> telemetry.milBlinkCode?.takeIf { it > 0 }?.let { "On ($it)" } ?: "On"
                        false -> "Off"
                        null -> "—"
                    }, "",
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VehicleMetric(
                    Modifier.weight(1f), Icons.Filled.Speed, "Instant economy",
                    telemetry.instantEconomyKmpl?.toString() ?: "—", "km/l",
                )
                VehicleMetric(
                    Modifier.weight(1f), Icons.Filled.Build, "Firmware",
                    telemetry.softwareVersion ?: "—", "",
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ConnectionPill(connected: Boolean, label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (connected) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (connected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (connected) "Connected" else label,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    connected: Boolean,
    stateLabel: String,
    freshness: String,
    onConnect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (connected) "Scooter connection is active" else "Your scooter is not connected",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                freshness,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (connected) "Change scooter" else if (stateLabel.contains("connect", true)) "Connect scooter" else "Find scooter")
            }
        }
    }
}

@Composable
private fun VehicleMetric(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
) {
    Card(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = NumberStyle.copy(fontSize = 20.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (unit.isNotBlank()) {
                Text(unit, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ToggleActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun NavigationStatusCard(hud: HudState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            hud.instructionTitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
            hud.street?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                if (hud.distanceMeters >= 0) "${hud.distanceMeters} m · ${hud.etaMinutes} min"
                else "${hud.etaMinutes} min to arrival",
                style = NumberStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LastParkedAction(parked: ParkedInfo?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Last parked", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    parked?.label ?: "No parking location recorded on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun openParkedLocation(context: android.content.Context, parked: ParkedInfo?): Boolean {
    val label = parked?.label ?: return false
    val parts = label.split(",").mapNotNull { it.trim().toDoubleOrNull() }
    if (parts.size != 2) return false
    val lat = parts[0]
    val lon = parts[1]
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Parked scooter)")
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
