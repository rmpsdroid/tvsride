package dev.tvsopen.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import dev.tvsopen.ui.theme.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.tvsopen.Nucleus
import dev.tvsopen.data.DataExport
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.ClusterFrames
import dev.tvsopen.protocol.Confidence
import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.Pictograms
import dev.tvsopen.transport.ConnectionState
import dev.tvsopen.transport.label
import dev.tvsopen.ui.theme.ThemeMode
import dev.tvsopen.ui.theme.ThemePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(
    connection: dev.tvsopen.session.ConnectionState1,
    riderName: String?,
    onRiderNameChange: (String?) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    autoConnect: Boolean,
    onAutoConnectChange: (Boolean) -> Unit,
    notificationAccessGranted: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    navMirroring: Boolean,
    onNavMirrorChange: (Boolean) -> Unit,
    rideMode: Boolean,
    onRideModeChange: (Boolean) -> Unit,
    onSetBrightness: (Int) -> Unit = {},
    onSetCalibration: (command: Int, seconds: Int) -> Unit = { _, _ -> },
    onOpenDiagnostics: () -> Unit = {},
) {
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val categories = SettingsCategory.values().toList()
    val capabilities = remember(connection.model) {
        DriverRegistry.forModel(connection.model).capabilities
    }
    val filteredCategories = remember(searchQuery) {
        val queryTerms = searchQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (queryTerms.isEmpty()) categories
        else categories.filter { category ->
            val searchable = "${category.title} ${category.description} ${category.searchTerms}".lowercase()
            queryTerms.all { term -> searchable.contains(term) }
        }
    }
    val selected = categories.firstOrNull { it.name == selectedCategory }
    BackHandler(enabled = selected != null) { selectedCategory = null }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selected == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Settings", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Make TVSRide work the way you ride",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Search settings") },
                    shape = RoundedCornerShape(14.dp),
                )
            }
            if (filteredCategories.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "No settings categories match “$searchQuery”. Try another search.",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(filteredCategories, key = { it.name }) { category ->
                    val summary = when (category) {
                        SettingsCategory.MY_SCOOTER ->
                            "${connection.model.displayName} · ${connection.state.label}"
                        SettingsCategory.CONNECTION ->
                            if (autoConnect) "Automatic connection is enabled" else "Automatic connection is off"
                        SettingsCategory.NAVIGATION ->
                            if (!capabilities.navigationHud) "Not supported by this scooter"
                            else if (navMirroring) "Turn-by-turn mirroring is on" else "Turn-by-turn mirroring is off"
                        SettingsCategory.CALLS_MESSAGES ->
                            if (!capabilities.callerId && !capabilities.textMessages) "Not supported by this scooter"
                            else if (notificationAccessGranted) "Notification access is granted" else "Notification access needed"
                        SettingsCategory.CLUSTER_DISPLAY ->
                            if (!capabilities.riderName && !capabilities.brightness && !capabilities.calibration)
                                "No cluster display controls available"
                            else riderName?.let { "Cluster greeting: $it" } ?: "Rider name, brightness and timers"
                        SettingsCategory.RIDE_PREFERENCES ->
                            if (!capabilities.keepalive) "Ride Mode control not supported"
                            else if (rideMode) "Ride Mode resumes after reboot" else "Ride Mode resume is off"
                        SettingsCategory.APPEARANCE -> "Theme and screen-awake preferences"
                        SettingsCategory.DATA_PRIVACY -> "Local ride history and data export"
                        SettingsCategory.ADVANCED -> "Diagnostics, protocol confidence and test information"
                        SettingsCategory.ABOUT -> "Version ${dev.tvsopen.BuildConfig.BUILD_VERSION_LABEL}"
                    }
                    SettingsCategoryRow(category, summary) { selectedCategory = category.name }
                }
            }
            item {
                Text(
                    "Your ride history and preferences are stored on this phone. Nothing is sent to a TVS account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedCategory = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(selected.title, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            selected.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                when (selected) {
                    SettingsCategory.MY_SCOOTER -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(connection.model.displayName, style = MaterialTheme.typography.titleLarge)
                                    Text("Bluetooth state: ${connection.state.label}")
                                    connection.address?.let {
                                        Text("Paired device: $it", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Find / change scooter")
                                }
                                OutlinedButton(
                                    onClick = onDisconnect,
                                    enabled = connection.state != ConnectionState.DISCONNECTED,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Disconnect") }
                            }
                        }
                    }

                    SettingsCategory.CONNECTION -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Bluetooth connection", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Current state: ${connection.state.label}. Auto-connect uses Android's nearby-device association when enabled.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            SettingsToggleCard(
                                "Auto-connect near vehicle",
                                "Connect when your associated scooter becomes reachable, even if TVSRide is closed.",
                                autoConnect,
                                onAutoConnectChange,
                            )
                            DozeCard()
                            OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Find or pair a scooter")
                            }
                        }
                    }

                    SettingsCategory.NAVIGATION -> {
                        val ctx = LocalContext.current
                        val prefs = remember { RidePrefs.load(ctx) }
                        var extras by remember { mutableStateOf(prefs.extraMapsPackages) }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsToggleCard(
                                "Navigation mirroring",
                                "Send turn-by-turn instructions from supported map apps to your scooter display.",
                                navMirroring,
                                onNavMirrorChange,
                            )
                            NotificationAccessCard(notificationAccessGranted, onOpenNotificationAccess)
                            ExtraPackageCard(
                                "Extra maps apps",
                                "Add map apps that are not detected automatically.",
                                extras,
                                listOf("map", "nav", "waze", "osm", "here", "tomtom", "route"),
                                onAdd = {
                                    val updated = extras + it
                                    extras = updated
                                    prefs.extraMapsPackages = updated
                                },
                                onRemove = {
                                    val updated = extras - it
                                    extras = updated
                                    prefs.extraMapsPackages = updated
                                },
                            )
                        }
                    }

                    SettingsCategory.CALLS_MESSAGES -> {
                        val ctx = LocalContext.current
                        val prefs = remember { RidePrefs.load(ctx) }
                        var mirrorCalls by remember { mutableStateOf(prefs.mirrorPhoneCalls) }
                        var mirrorMessages by remember { mutableStateOf(prefs.mirrorMessages) }
                        var mirrorAnyApp by remember { mutableStateOf(prefs.mirrorAnyAppNotifications) }
                        var mirrorText by remember { mutableStateOf(prefs.mirrorMessageText) }
                        var callDetails by remember { mutableStateOf(prefs.mirrorCallDetails) }
                        var extraMessages by remember { mutableStateOf(prefs.extraMessagePackages) }
                        var extraCalls by remember { mutableStateOf(prefs.extraCallPackages) }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            NotificationAccessCard(notificationAccessGranted, onOpenNotificationAccess)
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SettingsToggleRow(
                                        "Phone & app calls",
                                        "Show incoming calls from the phone, WhatsApp, Telegram and other supported apps.",
                                        mirrorCalls,
                                        { mirrorCalls = it; prefs.mirrorPhoneCalls = it },
                                    )
                                    HorizontalDivider()
                                    SettingsToggleRow(
                                        "Messages",
                                        "Show the sender and app for SMS, WhatsApp, Telegram and other supported messages.",
                                        mirrorMessages,
                                        { mirrorMessages = it; prefs.mirrorMessages = it },
                                    )
                                    HorizontalDivider()
                                    SettingsToggleRow(
                                        "Mirror any app's notifications",
                                        "Show non-system app notifications as messages. The Messages switch still applies.",
                                        mirrorAnyApp,
                                        { mirrorAnyApp = it; prefs.mirrorAnyAppNotifications = it },
                                    )
                                    HorizontalDivider()
                                    SettingsToggleRow(
                                        "Show message text",
                                        "Send up to 17 characters of the newest message body to the scooter display over Bluetooth.",
                                        mirrorText,
                                        { mirrorText = it; prefs.mirrorMessageText = it },
                                    )
                                    HorizontalDivider()
                                    SettingsToggleRow(
                                        "Show call type",
                                        "Add Missed or Out to the caller name when Android provides that information.",
                                        callDetails,
                                        { callDetails = it; prefs.mirrorCallDetails = it },
                                    )
                                }
                            }
                            ExtraPackageCard(
                                "Extra message apps",
                                "Add apps whose notifications should appear as messages.",
                                extraMessages,
                                listOf("whatsapp", "telegram", "message", "messenger", "signal", "discord", "slack", "chat", "sms"),
                                onAdd = { val updated = extraMessages + it; extraMessages = updated; prefs.extraMessagePackages = updated },
                                onRemove = { val updated = extraMessages - it; extraMessages = updated; prefs.extraMessagePackages = updated },
                            )
                            ExtraPackageCard(
                                "Extra call apps",
                                "Add apps whose incoming-call notifications should appear on the cluster.",
                                extraCalls,
                                listOf("whatsapp", "telegram", "call", "phone", "dialer", "meet", "duo"),
                                onAdd = { val updated = extraCalls + it; extraCalls = updated; prefs.extraCallPackages = updated },
                                onRemove = { val updated = extraCalls - it; extraCalls = updated; prefs.extraCallPackages = updated },
                            )
                        }
                    }

                    SettingsCategory.CLUSTER_DISPLAY -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            RiderNameCard(riderName, onRiderNameChange)
                            ClusterControlsCard(
                                connection.state == ConnectionState.CONNECTED,
                                onSetBrightness,
                                onSetCalibration,
                            )
                        }
                    }

                    SettingsCategory.RIDE_PREFERENCES -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsToggleCard(
                                "Keep Ride Mode active",
                                "Resume Ride Mode automatically after a scooter reboot.",
                                rideMode,
                                onRideModeChange,
                            )
                            KeepScreenOnCard(keepScreenOn, onKeepScreenOnChange)
                        }
                    }

                    SettingsCategory.APPEARANCE -> AppearanceCard()

                    SettingsCategory.DATA_PRIVACY -> {
                        val ctx = LocalContext.current
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Your data stays on this phone", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Ride history, settings and the last parked location are stored locally. Export only when you choose to share them.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(onClick = {
                                    val json = DataExport.buildJson(ctx)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_SUBJECT, "TVSRide data export")
                                        putExtra(Intent.EXTRA_TEXT, json)
                                    }
                                    ctx.startActivity(Intent.createChooser(intent, "Export data"))
                                }) { Text("Export & share data") }
                            }
                        }
                    }

                    SettingsCategory.ADVANCED -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Live connection logs", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Inspect connection events and raw Bluetooth frames when troubleshooting.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedButton(onClick = onOpenDiagnostics) {
                                        Icon(Icons.Filled.Science, null, Modifier.size(18.dp))
                                        Spacer(Modifier.size(6.dp))
                                        Text("Open diagnostics")
                                    }
                                }
                            }
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Vehicle capability confidence", style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold)
                                    Nucleus.describe().forEachIndexed { index, (model, facts) ->
                                        if (index > 0) HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                        Text(model.displayName, style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(top = 8.dp))
                                        facts.forEach { (fact, confidence) ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    when (confidence) {
                                                        Confidence.VERIFIED_FROM_APK -> "●"
                                                        Confidence.INHERITED_UNVERIFIED -> "◐"
                                                        Confidence.NEEDS_CAPTURE -> "○"
                                                    },
                                                    color = when (confidence) {
                                                        Confidence.VERIFIED_FROM_APK -> MaterialTheme.colorScheme.primary
                                                        Confidence.INHERITED_UNVERIFIED -> MaterialTheme.colorScheme.tertiary
                                                        Confidence.NEEDS_CAPTURE -> MaterialTheme.colorScheme.outline
                                                    },
                                                    modifier = Modifier.padding(end = 8.dp),
                                                )
                                                Text(fact, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    Text(
                                        "● verified from the official APK   ◐ inherited, unverified   ○ needs capture",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Pictograms (${Pictograms.ids.size})", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Official app table. Two tables disagree upstream — see docs/NAVIGATION.md if an arrow renders wrong.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Navigation display", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Instructions are compressed before being sent. For example: Turn left onto Example Road becomes <- LFT to Example Rd.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Arrow prefixes mark the turn (<- left, -> right, ^ straight, U U-turn, O roundabout). Text wider than the row scrolls instead of clipping.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Compression, arrows and scrolling run on the phone and are unit-tested. Cluster rendering still needs scooter verification.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    SettingsCategory.ABOUT -> {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary)
                                Text("TVSRide", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Build ${dev.tvsopen.BuildConfig.BUILD_VERSION_LABEL}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "No account. No TVS server. Internet access is used for map tiles only.",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private enum class SettingsCategory(
    val title: String,
    val description: String,
    val searchTerms: String,
    val icon: ImageVector,
) {
    MY_SCOOTER("My scooter", "Select, pair or disconnect your vehicle",
        "vehicle scooter model scan pairing bluetooth", Icons.Filled.Bluetooth),
    CONNECTION("Connection", "Auto-connect, reconnect and Android background settings",
        "bluetooth automatic reconnect battery doze background", Icons.Filled.Bluetooth),
    NAVIGATION("Navigation", "Maps and turn-by-turn instructions on your cluster",
        "maps waze route directions navigation mirroring", Icons.Filled.Navigation),
    CALLS_MESSAGES("Calls & messages", "Choose which notifications appear on your scooter",
        "phone calls call type sms whatsapp telegram notifications access message text", Icons.Filled.NotificationsActive),
    CLUSTER_DISPLAY("Cluster display", "Rider greeting, brightness and cluster timers",
        "name greeting brightness calibration timer display", Icons.Filled.Build),
    RIDE_PREFERENCES("Ride preferences", "Ride Mode and screen-awake behaviour",
        "ride mode resume reboot keep screen awake", Icons.Filled.Tune),
    APPEARANCE("Appearance", "Light, dark and system theme",
        "theme light dark colour color", Icons.Filled.Palette),
    DATA_PRIVACY("Data & privacy", "Local storage and export your data",
        "privacy local data export json rides history", Icons.Filled.Storage),
    ADVANCED("Advanced & diagnostics", "Logs, protocol confidence and technical details",
        "advanced diagnostics logs raw ble frames protocol pictograms", Icons.Filled.Science),
    ABOUT("About", "App version and project information",
        "version build app information support", Icons.Filled.Info),
}

@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    summary: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(category.icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(category.title, style = MaterialTheme.typography.titleMedium)
                Text(category.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summary, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        SettingsToggleRow(title, description, checked, onCheckedChange, Modifier.padding(16.dp))
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NotificationAccessCard(granted: Boolean, onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NotificationsActive, null,
                    tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.size(8.dp))
                Text(
                    if (granted) "Notification access is enabled" else "Notification access is needed",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                "Android uses this access to pass navigation, calls and messages to the scooter display. It is optional for basic Bluetooth telemetry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) OutlinedButton(onClick = onOpenSettings) {
                Text("Open notification access settings")
            }
        }
    }
}

private data class InstalledApp(val label: String, val packageName: String)

@Composable
private fun ExtraPackageCard(
    title: String,
    description: String,
    packages: Set<String>,
    hints: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (packages.isEmpty()) description
                else "Added: ${packages.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { open = true }) { Text("Manage") }
        }
    }

    if (open) {
        PackagePickerDialog(
            title = title,
            current = packages,
            hints = hints,
            onAdd = onAdd,
            onRemove = onRemove,
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun PackagePickerDialog(
    title: String,
    current: Set<String>,
    hints: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var installed by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledPackages(0)
                .mapNotNull { info ->
                    val label = runCatching {
                        info.applicationInfo?.loadLabel(pm)?.toString()
                    }.getOrNull() ?: return@mapNotNull null
                    InstalledApp(label, info.packageName)
                }
                .filter { it.packageName != ctx.packageName }
                .sortedBy { it.label.lowercase() }
        }
        installed = apps
        loading = false
    }

    val query = input.trim().lowercase()
    val matches = remember(query, installed) {
        val pool = if (query.isEmpty()) {
            installed.filter { app ->
                val text = "${app.label} ${app.packageName}".lowercase()
                !app.packageName.startsWith("com.android.internal") &&
                    !text.contains("navigation bar") &&
                    !text.contains("navbar") &&
                    hints.any { h -> text.contains(h) }
            }
        } else {
            installed.filter {
                it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
        pool.take(8)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Added", style = MaterialTheme.typography.bodyMedium)
                if (current.isEmpty()) {
                    Text(
                        "None yet.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    current.forEach { pkg ->
                        val label = installed.firstOrNull { it.packageName == pkg }?.label
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (label != null) {
                                    Text(label, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    pkg, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { onRemove(pkg) }) { Text("Remove") }
                        }
                    }
                }

                HorizontalDivider()
                Text("Installed apps", style = MaterialTheme.typography.bodyMedium)
                when {
                    loading -> Text(
                        "Loading installed apps…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    matches.isEmpty() -> Text(
                        if (query.isEmpty()) "No likely matches — type a package name below."
                        else "No installed app matches — add the package manually below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        matches.forEach { app ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        app.packageName, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedButton(onClick = { onAdd(app.packageName) }) { Text("Add") }
                            }
                        }
                        if (query.isNotEmpty()) {
                            Text(
                                "Showing the first ${matches.size} matches.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Filter apps / package name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(6.dp))
                    Button(onClick = {
                        val pkg = input.trim()
                        if (pkg.isNotEmpty()) {
                            onAdd(pkg)
                            input = ""
                        }
                    }) { Text("Add") }
                }
            }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun KeepScreenOnCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "For testing only. The screen stays awake while this app is " +
                            "open, which uses more battery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppSwitch(checked = enabled, onCheckedChange = onChange)
            }
        }
    }
}

@Composable
private fun RiderNameCard(name: String?, onNameChange: (String?) -> Unit) {
    var editing by remember(name) { mutableStateOf(name.orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Console greeting", style = MaterialTheme.typography.titleMedium)
            Text(
                "Shown by the cluster on ignition. Saving sends it at once when " +
                    "connected, otherwise on the next connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = editing,
                onValueChange = { if (it.length <= 17) editing = it },
                label = { Text("Rider name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("${editing.length}/17") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val trimmed = editing.trim().takeIf { it.isNotEmpty() }
                    onNameChange(trimmed)
                    editing = trimmed.orEmpty()
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    editing = ""
                    onNameChange(null)
                }) { Text("Clear") }
            }
            Text(
                if (name.isNullOrBlank()) "Cluster shows HELLO RIDER"
                else "Saved: $name — sent now if connected, else on next connection",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ClusterControlsCard(
    connected: Boolean,
    onSetBrightness: (Int) -> Unit,
    onSetCalibration: (command: Int, seconds: Int) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { RidePrefs.load(ctx) }
    var brightness by remember { mutableStateOf(prefs.clusterBrightness) }
    var followMeSec by remember { mutableStateOf(prefs.followMeTimerSec) }
    var idleStopSec by remember { mutableStateOf(prefs.idleStopTimerSec) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Cluster controls", style = MaterialTheme.typography.titleMedium)
            Text(
                if (connected) "Applies immediately on your Jupiter cluster."
                else "Connect to your scooter to adjust brightness and timers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Backlight brightness", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { lvl ->
                    FilterChip(
                        selected = brightness == lvl,
                        enabled = connected,
                        onClick = {
                            brightness = lvl
                            prefs.clusterBrightness = lvl
                            onSetBrightness(lvl)
                        },
                        label = { Text("$lvl") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            HorizontalDivider()
            Text("Follow-Me Headlamp timer", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Off", 10 to "10s", 20 to "20s", 30 to "30s").forEach { (sec, lbl) ->
                    FilterChip(
                        selected = followMeSec == sec,
                        enabled = connected,
                        onClick = {
                            followMeSec = sec
                            prefs.followMeTimerSec = sec
                            onSetCalibration(
                                ClusterFrames.CalibrationCommand.FOLLOW_ME_HEADLAMP_TIMER,
                                sec,
                            )
                        },
                        label = { Text(lbl) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            HorizontalDivider()
            Text("Idle Start-Stop (iGO Assist) timer", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5 to "5s", 10 to "10s", 15 to "15s", 20 to "20s").forEach { (sec, lbl) ->
                    FilterChip(
                        selected = idleStopSec == sec,
                        enabled = connected,
                        onClick = {
                            idleStopSec = sec
                            prefs.idleStopTimerSec = sec
                            onSetCalibration(
                                ClusterFrames.CalibrationCommand.IDLE_START_STOP_TIMER,
                                sec,
                            )
                        },
                        label = { Text(lbl) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DozeCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exempt by remember { mutableStateOf(isDozeExempt(ctx)) }
    val prefs = remember { RidePrefs.load(ctx) }

    LifecycleResumeEffect(Unit) {
        exempt = isDozeExempt(ctx)
        onPauseOrDispose { }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (exempt) "Battery optimisation: exempt"
                else "Battery optimisation: not exempt",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Without this the phone sleeps mid-ride and the cluster drops the " +
                    "connection. It does not give the app network access — it only " +
                    "stops Android suspending it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            if (!exempt) {
                OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}"),
                            ),
                        )
                    }.onFailure {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }
                    }
                    scope.launch {
                        delay(1500)
                        exempt = isDozeExempt(ctx)
                    }
                }) { Text("Open battery settings") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSwitch(
                    checked = prefs.resumeOnBoot,
                    onCheckedChange = { prefs.updateResumeOnBoot(it) },
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "Reconnect automatically after a reboot",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun isDozeExempt(ctx: Context): Boolean {
    val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return false
    return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
        .getOrDefault(false)
}

@Composable
private fun AppearanceCard() {
    val ctx = LocalContext.current
    val current = ThemePrefs.mode

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = current == mode,
                    onClick = { ThemePrefs.set(ctx, mode) },
                    label = { Text(mode.label, textAlign = TextAlign.Center) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
