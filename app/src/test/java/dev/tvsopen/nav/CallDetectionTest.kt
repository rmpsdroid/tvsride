package dev.tvsopen.nav

import org.junit.Assert.assertFalse
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
}
