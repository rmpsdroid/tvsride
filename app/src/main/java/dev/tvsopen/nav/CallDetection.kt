package dev.tvsopen.nav

internal data class CallerDetails(
    val number: String?,
    val name: String?,
)

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

    private val genericCallerLabels = setOf(
        "call",
        "incoming",
        "incoming call",
        "outgoing call",
        "ringing",
        "phone",
        "mobile",
        "home",
        "work",
        "unknown",
        "unknown caller",
        "private",
        "private number",
        "restricted",
        "withheld",
        "caller",
    )

    fun isPhoneSource(packageName: String): Boolean =
        packageName in phonePackages || packageName.startsWith("com.sec.android.")

    fun looksLikeCall(title: String, text: String): Boolean {
        val content = "$title $text".lowercase()
        return CALL_PHRASES.any { phrase -> content.contains(phrase) }
    }

    /** Returns the optional short label used for missed or outgoing calls. */
    fun callTypeLabel(title: String, rawCallType: String?, enabled: Boolean): String {
        if (!enabled) return ""
        return when {
            title.contains("missed", ignoreCase = true) -> "Missed"
            title.contains("outgoing", ignoreCase = true) || rawCallType == "2" -> "Out"
            else -> ""
        }
    }

    /**
     * Extract only a real-looking telephone number. Telecom exposes the
     * incoming address as a Uri on some Android builds, so callers must pass
     * its string form rather than assuming Bundle.getString() works.
     *
     * Notification text is only a fallback when it is itself phone-shaped;
     * labels such as "Mobile" and "Incoming call" are never treated as numbers.
     */
    fun extractCallerDetails(
        address: String?,
        title: String,
        text: String,
        sourceLabel: String? = null,
    ): CallerDetails {
        val addressNumber = addressNumber(address)
        val number = sequenceOf(addressNumber, title.trim(), text.trim())
            .filterNotNull()
            .firstOrNull(::isPhoneNumber)

        val name = sequenceOf(title, text)
            .map(::cleanCallerName)
            .firstOrNull { candidate ->
                candidate.isNotBlank() &&
                    candidate != number &&
                    !isPhoneNumber(candidate) &&
                    !isGenericCallerLabel(candidate, sourceLabel)
            }

        return CallerDetails(number, name)
    }

    fun isPhoneNumber(value: String): Boolean {
        val candidate = value.trim()
        val digitCount = candidate.count { it.isDigit() }
        return candidate.length <= 32 &&
            digitCount >= 5 &&
            candidate.all { it.isDigit() || it in "+-(). /" }
    }

    private fun addressNumber(address: String?): String? {
        var candidate = address?.trim().orEmpty()
        if (candidate.isBlank()) return null

        if (candidate.startsWith("tel:", ignoreCase = true)) {
            candidate = candidate.substringAfter(':')
        } else if (candidate.contains(':')) {
            // Do not mistake SIP addresses or arbitrary URI schemes for a
            // telephone number.
            return null
        }

        candidate = candidate
            .replace("%2B", "+", ignoreCase = true)
            .replace("%20", " ")
            .substringBefore(';')
            .substringBefore('?')
            .trim()

        return candidate.takeIf(::isPhoneNumber)
    }

    private fun cleanCallerName(value: String): String =
        value.trim()
            .replace(
                Regex("(?i)^incoming\\s+call(?:\\s+from)?\\s*[:,-]?\\s*"),
                "",
            )
            .replace(
                Regex("(?i)\\s+(?:is\\s+)?calling(?:\\.{3}|…)?$"),
                "",
            )
            .trim(' ', ':', '-', '—')

    private fun isGenericCallerLabel(value: String, sourceLabel: String?): Boolean {
        val normalized = value.trim().lowercase()
        return normalized in genericCallerLabels ||
            (!sourceLabel.isNullOrBlank() && normalized == sourceLabel.trim().lowercase())
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
