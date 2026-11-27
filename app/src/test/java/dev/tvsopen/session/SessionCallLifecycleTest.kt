package dev.tvsopen.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCallLifecycleTest {
    private fun resetSession() {
        Session.clearActiveCall()
        Session.onAlert(null)
    }

    @Test
    fun notificationRemovalDoesNotClearAnActiveSimCall() {
        resetSession()
        Session.setSimCallActive(true)
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.CALL,
                title = "Incoming call",
                key = "samsung-call-notification",
            ),
        )

        Session.clearAlert("samsung-call-notification")

        assertEquals(ClusterAlert.Kind.CALL, Session.alert.value?.kind)
        Session.clearActiveCall()
        assertNull(Session.alert.value)
    }

    @Test
    fun messageDoesNotReplaceAnActiveCallAndIsRestoredAfterCallEnds() {
        resetSession()
        Session.setSimCallActive(true)
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.CALL,
                title = "Alice",
                key = "samsung-call-notification",
                callerName = "Alice",
            ),
        )
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.MESSAGE,
                title = "Hello",
                key = "whatsapp-message",
                messageSender = "Bob",
                messageBody = "Hello",
            ),
        )

        assertEquals(ClusterAlert.Kind.CALL, Session.alert.value?.kind)

        Session.clearActiveCall()

        assertEquals(ClusterAlert.Kind.MESSAGE, Session.alert.value?.kind)
        assertEquals("Bob", Session.alert.value?.messageSender)
    }

    @Test
    fun messageRemovedDuringCallIsNotRestoredAfterCallEnds() {
        resetSession()
        Session.setSimCallActive(true)
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.CALL,
                title = "Alice",
                key = "samsung-call-notification",
            ),
        )
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.MESSAGE,
                title = "Hello",
                key = "whatsapp-message",
            ),
        )

        Session.clearAlert("whatsapp-message")
        Session.clearActiveCall()

        assertNull(Session.alert.value)
    }

    @Test
    fun notificationOnlyCallClearsWhenItsNotificationIsRemoved() {
        resetSession()
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.CALL,
                title = "Alice",
                key = "voip-call-notification",
                callerName = "Alice",
            ),
        )

        Session.clearAlert("voip-call-notification")

        assertNull(Session.alert.value)
    }
}
