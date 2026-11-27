package dev.tvsopen.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCallLifecycleTest {
    @Test
    fun notificationRemovalDoesNotClearAnActiveSimCall() {
        Session.clearActiveCall()
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.CALL,
                title = "Incoming call",
                key = "samsung-call-notification",
                callerName = "Incoming call",
            ),
        )
        Session.setSimCallActive(true)

        Session.clearAlert("samsung-call-notification")

        assertEquals(ClusterAlert.Kind.CALL, Session.alert.value?.kind)
        Session.clearActiveCall()
        assertNull(Session.alert.value)
    }

    @Test
    fun notificationOnlyCallClearsWhenItsNotificationIsRemoved() {
        Session.clearActiveCall()
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
