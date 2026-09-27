package com.ridelink.app.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ridelink.app.MainActivity
import com.ridelink.app.R
import com.ridelink.app.RideLinkApplication
import com.ridelink.app.nearby.NearbyState
import com.ridelink.app.nearby.formatPeerName
import com.ridelink.app.voicechat.VoiceChatState

/**
 * Builds the single persistent notification for a role (Rider or Pillion):
 * a quiet, low-priority status while not yet connected, and -- once
 * connected -- a high-priority notification whose action buttons track
 * [VoiceChatState]. Stateless: callers decide [alert] (whether this
 * specific update should make a sound/heads-up rather than update quietly)
 * by comparing against the previous state themselves -- see
 * RiderForegroundService/PillionForegroundService's collectors.
 */
object ConnectionNotificationBuilder {

    fun build(
        context: Context,
        role: ConnectionRole,
        nearbyState: NearbyState,
        callState: VoiceChatState,
        isMuted: Boolean,
        alert: Boolean,
    ): Notification = when (nearbyState) {
        is NearbyState.Idle, is NearbyState.Searching -> statusNotification(
            context, role,
            when (role) {
                ConnectionRole.RIDER -> "Listening for your pillion's route..."
                ConnectionRole.PILLION -> "Waiting for your rider to connect..."
            },
        )
        is NearbyState.Disconnected -> statusNotification(
            context, role, "Disconnected -- reopen RideLink to reconnect",
        )
        is NearbyState.Connected -> connectedNotification(
            context, role, formatPeerName(nearbyState.endpointName), callState, isMuted, alert,
        )
    }

    private fun statusNotification(context: Context, role: ConnectionRole, text: String): Notification {
        val channel = when (role) {
            ConnectionRole.RIDER -> RideLinkApplication.RIDER_STATUS_CHANNEL_ID
            ConnectionRole.PILLION -> RideLinkApplication.PILLION_STATUS_CHANNEL_ID
        }
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_rider)
            .setContentTitle("RideLink")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(context))
            .build()
    }

    private fun connectedNotification(
        context: Context,
        role: ConnectionRole,
        peerName: String,
        callState: VoiceChatState,
        isMuted: Boolean,
        alert: Boolean,
    ): Notification {
        val (text, actions) = when (callState) {
            VoiceChatState.IDLE -> "Connected to $peerName" to listOf(
                action(context, role, NotificationActionReceiver.ACTION_OFFER, "Voice Chat", 0),
                disconnectAction(context, role),
            )
            VoiceChatState.OFFERING -> "Calling $peerName..." to listOf(
                action(context, role, NotificationActionReceiver.ACTION_CANCEL_OFFER, "Waiting to accept...", 1),
                disconnectAction(context, role),
            )
            VoiceChatState.INCOMING_OFFER -> "$peerName wants to start voice chat" to listOf(
                action(context, role, NotificationActionReceiver.ACTION_ACCEPT, "Accept", 2),
                action(context, role, NotificationActionReceiver.ACTION_DECLINE, "Decline", 3),
                disconnectAction(context, role),
            )
            VoiceChatState.ACTIVE -> "Voice chat active with $peerName" to listOf(
                action(
                    context, role, NotificationActionReceiver.ACTION_TOGGLE_MUTE,
                    if (isMuted) "Unmute" else "Mute", 4,
                ),
                action(context, role, NotificationActionReceiver.ACTION_END_CALL, "End", 5),
                disconnectAction(context, role),
            )
        }

        val builder = NotificationCompat.Builder(context, RideLinkApplication.CONNECTION_ACTIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_rider)
            .setContentTitle("RideLink")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent(context))
        actions.forEach(builder::addAction)
        return builder.build()
    }

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE_CONTENT,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun disconnectAction(context: Context, role: ConnectionRole): NotificationCompat.Action =
        action(context, role, NotificationActionReceiver.ACTION_DISCONNECT, "Disconnect", 6)

    private fun action(
        context: Context,
        role: ConnectionRole,
        actionName: String,
        title: String,
        requestCodeBase: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = actionName
            putExtra(NotificationActionReceiver.EXTRA_ROLE, role.name)
        }
        // Distinct per role so Rider's and Pillion's action PendingIntents
        // never collide -- filterEquals() ignores extras, so the role alone
        // wouldn't be enough to distinguish them without this.
        val requestCode = requestCodeBase + if (role == ConnectionRole.RIDER) 0 else 100
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification_rider, title, pendingIntent).build()
    }

    private const val REQUEST_CODE_CONTENT = 0
}
