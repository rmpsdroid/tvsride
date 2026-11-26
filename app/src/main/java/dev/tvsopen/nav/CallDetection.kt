package dev.tvsopen.nav

/**
 * Pure helpers for identifying phone-call notifications before generic
 * system-notification filtering is applied.
 */
internal object CallDetection {
    private val phonePackages = setOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
    )

    fun isPhoneSource(packageName: String): Boolean =
        packageName in phonePackages || packageName.startsWith("com.sec.android.")

    fun looksLikeCall(title: String, text: String): Boolean {
        val content = "$title $text".lowercase()
        return CALL_PHRASES.any(content::contains)
    }

    private val CALL_PHRASES = listOf(
        "incoming call",
        "voice call",
        "video call",
        "is calling",
        "calling...",
        "calling…",
        "ringing",
        "incoming",
    )
}
