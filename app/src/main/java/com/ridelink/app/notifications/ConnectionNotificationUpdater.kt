package com.ridelink.app.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.ridelink.app.AppVisibility
import com.ridelink.app.nearby.NearbyState
import com.ridelink.app.voicechat.VoiceChatSession
import com.ridelink.app.voicechat.VoiceChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Re-posts the role's single connected-status notification whenever anything
 * it shows changes: the connection, the call state, mute, the audio output,
 * or whether the app is on screen. Shared by both roles' foreground services.
 */
internal fun CoroutineScope.launchConnectionNotificationUpdates(
    context: Context,
    role: ConnectionRole,
    notificationId: Int,
    connectionState: StateFlow<NearbyState>,
    voiceChat: VoiceChatSession,
) {
    val policy = AlertPolicy()
    launch {
        combine(
            connectionState,
            voiceChat.callState,
            voiceChat.isMuted,
            voiceChat.routing.selected,
            AppVisibility.isForeground,
        ) { nearbyState, callState, _, _, foreground -> Inputs(nearbyState, callState, foreground) }
            .collect { (nearbyState, callState, foreground) ->
                val alert = policy.shouldAlert(
                    connected = nearbyState is NearbyState.Connected,
                    incomingOffer = callState == VoiceChatState.INCOMING_OFFER,
                    foreground = foreground,
                )
                NotificationManagerCompat.from(context).notify(
                    notificationId,
                    ConnectionNotificationBuilder.build(context, role, nearbyState, callState, voiceChat, alert),
                )
            }
    }
}

private data class Inputs(
    val nearbyState: NearbyState,
    val callState: VoiceChatState,
    val foreground: Boolean,
)

/**
 * Decides which updates may make a sound / heads-up. Only two moments are
 * worth interrupting for -- just paired, and an incoming voice request -- and
 * never while the app is already open (the in-app screen shows both). A
 * request that arrived while the app was open does alert once the user
 * leaves the app, so it isn't missed.
 */
private class AlertPolicy {
    private var wasConnected = false
    private var wasIncomingOffer = false
    private var wasForeground: Boolean? = null

    fun shouldAlert(connected: Boolean, incomingOffer: Boolean, foreground: Boolean): Boolean {
        val justConnected = connected && !wasConnected
        val justRinging = incomingOffer && !wasIncomingOffer
        val leftAppWhileRinging = incomingOffer && !foreground && wasForeground == true
        wasConnected = connected
        wasIncomingOffer = incomingOffer
        wasForeground = foreground
        return (!foreground && (justConnected || justRinging)) || leftAppWhileRinging
    }
}
