package com.ridelink.app.notifications

import com.ridelink.app.pillion.PillionSession
import com.ridelink.app.rider.RiderSession

/**
 * Ends the role's session and its foreground service. Used by the
 * notification's Disconnect button and the in-app End button.
 *
 * A direct call on the running service, never a start-service intent: if the
 * service is already gone, Android would require that intent's target to call
 * startForeground() and crash the app when it doesn't. With no service
 * running there is nothing to stop but the session itself.
 */
fun stopConnection(role: ConnectionRole) {
    val host = ConnectionNotificationHost.current
    if (host != null) {
        host.stopListening()
        return
    }
    when (role) {
        ConnectionRole.RIDER -> RiderSession.reset()
        ConnectionRole.PILLION -> PillionSession.reset()
    }
}
