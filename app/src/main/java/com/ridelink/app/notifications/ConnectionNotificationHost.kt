package com.ridelink.app.notifications

/**
 * Lets [com.ridelink.app.voicechat.VoiceChatSession] trigger the owning
 * foreground service's foreground-service-type change (adding/removing
 * "microphone" for the duration of a call) without depending on
 * RiderForegroundService/PillionForegroundService directly. Whichever
 * role's service is currently running registers itself as [current] --
 * only one role ever runs per install, so a single holder is enough.
 *
 * Also the one way to stop that service from the notification's Disconnect
 * button or the in-app End button: a plain method call. Sending a stop
 * *intent* via startForegroundService() is unsafe, because if the service is
 * no longer running Android expects it to call startForeground() and kills
 * the app when it never does.
 */
interface ConnectionNotificationHost {
    /**
     * Adds the microphone type (held from the moment a call is offered or
     * accepted, while the user's tap still allows it, until the call is over).
     * Returns false if Android refused -- the caller must then not start a call.
     */
    fun enterCallType(): Boolean

    /** Reverts to this role's normal (non-call) foreground-service type. */
    fun exitCallType()

    /** Ends the session, removes the notification and stops the service. */
    fun stopListening()

    companion object {
        var current: ConnectionNotificationHost? = null
    }
}
