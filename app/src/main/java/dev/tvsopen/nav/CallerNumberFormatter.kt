package dev.tvsopen.nav

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

internal data class CallerNumberParts(
    val nationalNumber: String,
    val countryCode: String?,
)

/**
 * Jupiter's 0x43 caller protocol has separate national-number and country-code
 * fields. Android notifications commonly provide one E.164 number, so split
 * it before building the cluster frames.
 */
internal object CallerNumberFormatter {
    private val phoneUtil = PhoneNumberUtil.getInstance()

    fun split(number: String?, defaultRegion: String?): CallerNumberParts {
        val raw = number?.trim().orEmpty()
        if (raw.isBlank()) return CallerNumberParts("", null)

        val region = defaultRegion
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.length == 2 }

        val parsed = runCatching {
            when {
                raw.startsWith("+") -> phoneUtil.parse(raw, null)
                region != null -> phoneUtil.parse(raw, region)
                else -> null
            }
        }.getOrNull()

        if (parsed != null && phoneUtil.isPossibleNumber(parsed)) {
            return CallerNumberParts(
                nationalNumber = phoneUtil.getNationalSignificantNumber(parsed),
                countryCode = parsed.countryCode.toString(),
            )
        }

        // Preserve a usable number if the carrier's region is unavailable or
        // the input is not parseable. Never invent a country code.
        return CallerNumberParts(
            nationalNumber = raw.filter(Char::isDigit),
            countryCode = null,
        )
    }
}
