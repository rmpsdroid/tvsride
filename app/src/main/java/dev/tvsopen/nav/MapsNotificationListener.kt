package dev.tvsopen.nav

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.telephony.TelephonyManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.session.ClusterAlert
import dev.tvsopen.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NavInstruction(
    val title: String,
    val street: String,
    val distanceMeters: Int,
    val etaMinutes: Int = 0,
    val pictogram: Int?,
    val provider: String,
) {
    fun displayLine(maxChars: Int = RowChars.NTORQ, tick: Int = 0): String {
        val combined = listOf(title, street)
            .firstOrNull { it.isNotBlank() } ?: "Navigating"
        val compressed = NavShortCodes.compress(combined)
        val arrow = NavShortCodes.asciiArrow(pictogram)
        val full = if (arrow.isNotEmpty()) "$arrow $compressed" else compressed
        return RollingText.window(full, maxChars, tick)
    }

    fun metricsLine(maxChars: Int = RowChars.NTORQ_LINE_1, tick: Int = 0): String {
        val d = when {
            distanceMeters < 0 -> ""
            distanceMeters >= 1000 -> "${distanceMeters / 1000}Km ${distanceMeters % 1000}m"
            else -> "${distanceMeters}m"
        }
        val raw = listOf(
            if (etaMinutes > 0) "ETA ${etaMinutes}m" else "",
            d,
        ).filter { it.isNotEmpty() }.joinToString(" ")
        return RollingText.window(NavShortCodes.compress(raw), maxChars, tick)
    }
}

class MapsNotificationListener : NotificationListenerService() {

    private val _instruction = MutableStateFlow<NavInstruction?>(null)
    val instruction: StateFlow<NavInstruction?> = _instruction.asStateFlow()

    private val _navigating = MutableStateFlow(false)
    val navigating: StateFlow<Boolean> = _navigating.asStateFlow()

    private fun allowedPackages(): Set<String> =
        DEFAULT_MAPS_PACKAGES + RidePrefs.load(applicationContext).extraMapsPackages

    @Volatile
    private var activeNavKey: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName
        val extras = n.notification?.extras ?: return
        if (pkg.isBlank()) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val ongoing = n.isOngoing
        val category = n.notification?.category

        // Phone/Telecom notifications must be classified before the generic
        // system-package exclusion: Samsung's in-call UI is a system package.
        val prefs = RidePrefs.load(this)
        val structuralCall = category == Notification.CATEGORY_CALL ||
            extras.containsKey("android.telecom.extra.INCOMING_CALL_EXTRAS") ||
            extras.containsKey("android.telecom.extra.INCOMING_CALL_ADDRESS")
        val phoneSourceCall = CallDetection.isPhoneSource(pkg) &&
            (structuralCall || CallDetection.looksLikeCall(title, text))
        val selectedCall = pkg in prefs.extraCallPackages && CallDetection.looksLikeCall(title, text)
        val anyAppCall = prefs.mirrorAnyAppNotifications && CallDetection.looksLikeCall(title, text)
        if (structuralCall || phoneSourceCall || selectedCall || anyAppCall) {
            Log.i(TAG, "call notification observed from $pkg (category=$category, ongoing=$ongoing)")
            if (prefs.mirrorPhoneCalls) postCall(n.key, pkg, extras, title, text)
            return
        }

        if (isSystemNotification(pkg)) return

        val parsed = NavParsing.parse(title, text, bigText, subText)
        val distance = parsed.distanceMeters

        val hasNavExtras = extras.containsKey("android.app.extra.MAPS_MODE") ||
            extras.containsKey("navigation.mode")
        val looksLikeNav = pkg in allowedPackages() || hasNavExtras ||
            (ongoing && category == Notification.CATEGORY_NAVIGATION &&
                title.isNotEmpty() && (text.isNotEmpty() || bigText.isNotEmpty()) &&
                distance >= 0)

        if (looksLikeNav) {
            postNav(pkg, parsed, n.key)
            return
        }

        val hasMessageStyle = extractMessagingStyle(extras) != null
        val selectedMessage = pkg in prefs.extraMessagePackages
        val anyAppMessage = prefs.mirrorAnyAppNotifications && !ongoing
        val isMessage = hasMessageStyle || category == Notification.CATEGORY_MESSAGE ||
            selectedMessage || anyAppMessage
        if (isMessage && prefs.mirrorMessages) {
            postMessage(n.key, pkg, extras, title, bigText.ifBlank { text })
        }
    }

    private fun isSystemNotification(pkg: String): Boolean =
        pkg == packageName || pkg == "com.android.systemui" ||
            pkg == "android" || pkg.startsWith("com.android.") ||
            pkg.startsWith("com.samsung.android.app.routines") ||
            pkg.startsWith("com.samsung.android.lool") ||
            pkg.startsWith("com.samsung.android.incallui") ||
            pkg.startsWith("com.sec.android.") ||
            pkg == "com.google.android.gms"

    private fun postNav(pkg: String, parsed: ParsedNav, key: String) {
        activeNavKey = key
        val step = NavInstruction(
            title = parsed.maneuver,
            street = parsed.street,
            distanceMeters = parsed.distanceMeters,
            etaMinutes = parsed.etaMinutes,
            pictogram = parsed.pictogram,
            provider = pkg,
        )
        Log.i(
            TAG,
            "nav from $pkg: '${step.title}' / '${step.street}' " +
                "d=${step.distanceMeters}m eta=${step.etaMinutes}m pic=${step.pictogram}",
        )
        Session.onHud {
            it.copy(
                active = true,
                instructionTitle = step.title,
                street = step.street,
                distanceMeters = step.distanceMeters,
                etaMinutes = step.etaMinutes,
                pictogramId = step.pictogram,
                provider = step.provider,
            )
        }
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = null
        _instruction.value = step
        _navigating.value = true
    }

    private fun postCall(key: String, pkg: String, extras: Bundle, title: String, text: String) {
        cancelPendingMessageClear()

        val incomingExtras = extras.getBundle("android.telecom.extra.INCOMING_CALL_EXTRAS")
        val address = extras.get("android.telecom.extra.INCOMING_CALL_ADDRESS")?.toString()
        val additionalNumbers = listOfNotNull(
            extras.get("android.telecom.extra.CALLER_NUMBER")?.toString(),
            extras.get("android.phone.extra.CALLER_NUMBER")?.toString(),
            incomingExtras?.get("android.telecom.extra.INCOMING_CALL_ADDRESS")?.toString(),
        )
        val additionalNames = listOfNotNull(
            callPersonName(extras),
            extras.getCharSequence("android.telecom.extra.CALLER_NAME")?.toString(),
            extras.getCharSequence("android.telecom.extra.CALLER_DISPLAY_NAME")?.toString(),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            incomingExtras?.getCharSequence("android.telecom.extra.CALLER_NAME")?.toString(),
        )
        val caller = CallDetection.extractCallerDetails(
            address = address,
            title = title,
            text = text,
            sourceLabel = appLabel(pkg),
            additionalNumbers = additionalNumbers,
            additionalNames = additionalNames,
        )
        val telephony = getSystemService(TelephonyManager::class.java)
        val region = telephony?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
        val numberParts = CallerNumberFormatter.split(caller.number, region)
        val nationalNumber = numberParts.nationalNumber.takeIf { it.isNotBlank() }
        val name = caller.name ?: "Incoming call"
        val callType = extras.get("android.telecom.extra.CALL_TYPE")?.toString()
        val typeLabel = CallDetection.callTypeLabel(
            title = "$title $text",
            rawCallType = callType,
            enabled = RidePrefs.load(this).mirrorCallDetails,
        )
        val displayName = if (
            typeLabel.isBlank() || name.startsWith(typeLabel, ignoreCase = true)
        ) name else "$typeLabel $name"

        Log.i(
            TAG,
            "call alert prepared from $pkg " +
                "(national number available=${nationalNumber != null}, " +
                "country code available=${numberParts.countryCode != null}, " +
                "contact name available=${caller.name != null})",
        )
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.CALL,
                title = displayName,
                subtitle = caller.number.orEmpty(),
                key = key,
                callerNumber = nationalNumber,
                callerCountryCode = numberParts.countryCode,
                callerName = displayName,
            ),
        )
    }

    private fun postMessage(
        key: String,
        pkg: String,
        extras: Bundle,
        title: String,
        fallbackBody: String,
    ) {
        cancelPendingMessageClear()
        val info = extractMessageInfo(extras, title, fallbackBody)
        val app = appLabel(pkg)
        val withText = RidePrefs.load(this).mirrorMessageText && info.body.isNotBlank()
        val display = if (withText) {
            MessageTextFormatter.formatForCluster(info.title, info.body)
        } else when {
            info.title.isNotBlank() && info.count > 1 -> "${info.title} ($app ${info.count})"
            info.title.isNotBlank() && app.isNotBlank() -> "${info.title} ($app)"
            info.title.isNotBlank() -> info.title
            info.count > 1 -> "$app: ${info.count} msgs"
            else -> app.ifBlank { "Message" }
        }
        // Do not write message contents to the diagnostic log when text mirroring is enabled.
        Log.i(
            TAG,
            "message from $pkg (text mirrored=$withText, sender available=${info.title.isNotBlank()})",
        )
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.MESSAGE,
                title = display,
                subtitle = app,
                key = key,
            ),
        )
    }

    private data class MessageInfo(val title: String, val count: Int, val body: String)

    private fun extractMessageInfo(
        extras: Bundle,
        fallbackTitle: String,
        fallbackBody: String,
    ): MessageInfo {
        val style = extractMessagingStyle(extras)
        if (style != null) {
            val conv = style.conversationTitle?.toString()?.trim().orEmpty()
            val messages = style.messages
            val count = messages.size
            val newest = messages.lastOrNull()
            // In group conversations the conversation title is the group,
            // not the person who sent the newest message.
            val sender = newest?.senderPerson?.name?.toString()?.trim().orEmpty()
                .ifBlank { conv }
            val body = newest?.text?.toString()?.trim().orEmpty().ifBlank { fallbackBody }
            return MessageInfo(sender.ifBlank { fallbackTitle }, count.coerceAtLeast(1), body)
        }
        return MessageInfo(fallbackTitle, 1, fallbackBody)
    }

    @Suppress("DEPRECATION")
    private fun callPersonName(extras: Bundle): String? {
        val person = extras.getParcelable<Parcelable>("android.callPerson") as? Person
        return person?.name?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun cancelPendingMessageClear() {
        pendingMessageClear?.let { handler.removeCallbacks(it) }
        pendingMessageClear = null
    }

    @Suppress("DEPRECATION")
    private fun extractMessagingStyle(extras: Bundle): Notification.MessagingStyle? {
        val p = extras.getParcelable<android.os.Parcelable>("android.messagingStyle")
            ?: extras.getParcelable<android.os.Parcelable>(EXTRA_MESSAGING_STYLE_KEY)
        return p as? Notification.MessagingStyle
    }

    private fun appLabel(pkg: String): String = when (pkg) {
        "com.whatsapp" -> "WhatsApp"
        "org.telegram.messenger", "org.telegram.messenger.web" -> "Telegram"
        "com.google.android.apps.messaging" -> "Messages"
        "com.facebook.orca" -> "Messenger"
        "com.instagram.android" -> "Instagram"
        "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer" -> "Phone"
        else -> pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val removed = sbn ?: return
        val key = removed.key
        val currentAlert = Session.alert.value
        if (currentAlert?.kind == ClusterAlert.Kind.MESSAGE && currentAlert.key == key) {
            cancelPendingMessageClear()
            pendingMessageClear = Runnable {
                Session.clearAlert(key)
                pendingMessageClear = null
            }.also { handler.postDelayed(it, MESSAGE_CLEAR_GRACE_MS) }
        } else {
            Session.clearAlert(key)
        }

        if (activeNavKey == null || removed.key != activeNavKey) return
        activeNavKey = null
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = Runnable {
            Log.i(TAG, "navigation ended (${removed.packageName})")
            Session.onHud { it.copy(active = false) }
            _navigating.value = false
            _instruction.value = null
        }.also { handler.postDelayed(it, CLEAR_GRACE_MS) }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingClear: Runnable? = null
    private var pendingMessageClear: Runnable? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected; allowed: ${allowedPackages().joinToString()}")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "listener disconnected — navigation mirroring unavailable")
        runCatching {
            requestRebind(ComponentName(this, MapsNotificationListener::class.java))
        }.onFailure { Log.w(TAG, "rebind request failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "MapsNavListener"
        private const val EXTRA_MESSAGING_STYLE_KEY = "android.messagingStyle"
        private const val CLEAR_GRACE_MS = 12_000L
        private const val MESSAGE_CLEAR_GRACE_MS = 5_000L

        val DEFAULT_MAPS_PACKAGES = setOf(
            "com.google.android.apps.maps",
            "com.mappls.app",
            "com.mmi.android.mmaps",
            "org.organicmaps",
            "com.here.app.maps",
            "com.waze",
            "com.tomtom.gotcha",
            "com.transit.app.transitmaps",
            "com.immobiletrack",
        )
    }
}

object RowChars {
    const val NTORQ_LINE_1 = 10
    const val NTORQ_LINE_2 = 12
    const val NTORQ = NTORQ_LINE_2
    const val JUPITER = 17
}
