package com.ridelink.app.notifications

/**
 * Lets [com.ridelink.app.voicechat.VoiceChatSession] trigger the owning
 * foreground service's foreground-service-type change (adding/removing
 * "microphone" for the duration of a call) without depending on
 * RiderForegroundService/PillionForegroundService directly. Whichever
 * role's service is currently running registers itself as [current] --
 * only one role ever runs per install, so a single holder is enough.
 */
interface ConnectionNotificationHost {
    /** Adds the microphone type for the duration of an active call. */
    fun enterCallType()

    /** Reverts to this role's normal (non-call) foreground-service type. */
    fun exitCallType()

    companion object {
        var current: ConnectionNotificationHost? = null
    }
}
