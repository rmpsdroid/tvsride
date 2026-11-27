package dev.tvsopen.nav

import java.util.Locale

/** Builds a sender-first SMS line that fits the cluster's 17-character field. */
internal object MessageTextFormatter {
    private val genericSenders = setOf(
        "messages",
        "sms",
        "new message",
        "new messages",
        "whatsapp",
        "telegram",
        "messenger",
        "instagram",
        "notification",
    )

    fun formatForCluster(sender: String, body: String, maxChars: Int = 17): String {
        if (maxChars <= 0) return ""

        val cleanSender = sender.trim().takeUnless {
            it.isBlank() || it.lowercase(Locale.ROOT) in genericSenders
        }
        val cleanBody = body.trim()

        if (cleanSender == null) return cleanBody.take(maxChars)
        if (cleanBody.isBlank()) return cleanSender.take(maxChars)

        val prefix = "$cleanSender: "
        if (prefix.length >= maxChars) return cleanSender.take(maxChars)
        return prefix + cleanBody.take(maxChars - prefix.length)
    }
}
