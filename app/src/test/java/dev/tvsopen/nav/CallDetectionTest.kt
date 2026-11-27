package dev.tvsopen.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallDetectionTest {
    @Test
    fun samsungInCallUiIsRecognizedAsPhoneSource() {
        assertTrue(CallDetection.isPhoneSource("com.samsung.android.incallui"))
    }

    @Test
    fun samsungDialerIsRecognizedAsPhoneSource() {
        assertTrue(CallDetection.isPhoneSource("com.samsung.android.dialer"))
    }

    @Test
    fun androidTelecomIsRecognizedAsPhoneSource() {
        assertTrue(CallDetection.isPhoneSource("com.android.server.telecom"))
    }

    @Test
    fun phoneCallPhrasesAreRecognizedCaseInsensitively() {
        assertTrue(CallDetection.looksLikeCall("Incoming call", "Mobile"))
        assertTrue(CallDetection.looksLikeCall("John", "is calling"))
        assertTrue(CallDetection.looksLikeCall("", "RINGING"))
    }

    @Test
    fun unrelatedNotificationsAreNotCallText() {
        assertFalse(CallDetection.looksLikeCall("Google Maps", "Turn left in 200 m"))
        assertFalse(CallDetection.looksLikeCall("Battery", "Charging"))
    }

    @Test
    fun callTypeLabelsAreOptInAndUseTheAvailableCallType() {
        assertEquals("", CallDetection.callTypeLabel("Missed call", null, false))
        assertEquals("Missed", CallDetection.callTypeLabel("Missed call from Alice", null, true))
        assertEquals("Out", CallDetection.callTypeLabel("Alice", "2", true))
        assertEquals("Out", CallDetection.callTypeLabel("Outgoing call", null, true))
        assertEquals("", CallDetection.callTypeLabel("Incoming call", "1", true))
    }

    @Test
    fun telecomTelUriIsExtractedAsTheCallerNumber() {
        val caller = CallDetection.extractCallerDetails(
            address = "tel:+1-202-555-0147",
            title = "Incoming call",
            text = "Mobile",
            sourceLabel = "Incallui",
        )
        assertEquals("+1-202-555-0147", caller.number)
        assertNull(caller.name)
    }

    @Test
    fun notificationTitleCanProvideTheContactName() {
        val caller = CallDetection.extractCallerDetails(
            address = "tel:+91 98765 43210",
            title = "Alice Smith",
            text = "Mobile",
            sourceLabel = "Phone",
        )
        assertEquals("+91 98765 43210", caller.number)
        assertEquals("Alice Smith", caller.name)
    }

    @Test
    fun ordinaryNotificationCopyIsNeverInventedAsANumberOrName() {
        val caller = CallDetection.extractCallerDetails(
            address = null,
            title = "Incoming call",
            text = "Mobile",
            sourceLabel = "Incallui",
        )
        assertNull(caller.number)
        assertNull(caller.name)
    }

    @Test
    fun phoneNumberInTitleIsNotMistakenForAContactName() {
        val caller = CallDetection.extractCallerDetails(
            address = null,
            title = "+919876543210",
            text = "Mobile",
        )
        assertEquals("+919876543210", caller.number)
        assertNull(caller.name)
    }

    @Test
    fun callingSuffixIsRemovedFromContactName() {
        val caller = CallDetection.extractCallerDetails(
            address = null,
            title = "Alice is calling",
            text = "",
        )
        assertEquals("Alice", caller.name)
    }
}
