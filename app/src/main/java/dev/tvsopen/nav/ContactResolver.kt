package dev.tvsopen.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Resolves a phone-number label against the phone's local contacts.
 *
 * Contact data is read only on-device and is never written to diagnostics or
 * sent anywhere except the already-authorized caller/message display packet.
 */
internal object ContactResolver {
    private val phonePattern = Regex("""^\+?[0-9\s().-]{6,}$""")

    fun lookupName(context: Context, rawNumber: String?): String? {
        val number = rawNumber?.trim()?.takeIf { phonePattern.matches(it) } ?: return null
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
