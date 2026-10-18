package com.ridelink.app.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
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
 * [VoiceChatState]. All four connected states show their buttons without
 * expanding: the three true call states (offering/incoming/active) use
 * [NotificationCompat.CallStyle], whose always-visible-actions behavior is
 * OS-native on API 31+; the idle state isn't a call, so it uses a custom
 * [RemoteViews] layout (`R.layout.notification_idle_actions`) via
 * [NotificationCompat.DecoratedCustomViewStyle] instead. Stateless: callers
 * decide [alert] (whether this specific update should make a sound/heads-up
 * rather than update quietly) by comparing against the previous state
 * themselves -- see RiderForegroundService/PillionForegroundService's
 * collectors.
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
        val builder = NotificationCompat.Builder(context, RideLinkApplication.CONNECTION_ACTIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_rider)
            .setContentTitle("RideLink")
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent(context))

        when (callState) {
            VoiceChatState.IDLE -> {
                // Not a call, so CallStyle can't represent this state -- the
                // only way to get real buttons into the *collapsed* view
                // here is a custom layout via DecoratedCustomViewStyle.
                val text = "Connected to $peerName"
                val views = RemoteViews(context.packageName, R.layout.notification_idle_actions).apply {
                    setTextViewText(R.id.notification_idle_title, text)
                    setOnClickPendingIntent(
                        R.id.notification_idle_voice_chat,
                        actionPendingIntent(context, role, NotificationActionReceiver.ACTION_OFFER, REQUEST_CODE_OFFER),
                    )
                    setOnClickPendingIntent(
                        R.id.notification_idle_disconnect,
                        actionPendingIntent(context, role, NotificationActionReceiver.ACTION_DISCONNECT, REQUEST_CODE_DISCONNECT),
                    )
                }
                builder
                    .setContentText(text)
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomContentView(views)
                    // Mirrored so the expanded view and Wear/accessibility
                    // surfaces stay consistent with the custom view's buttons.
                    .addAction(action(context, role, NotificationActionReceiver.ACTION_OFFER, "Voice Chat", REQUEST_CODE_OFFER))
                    .addAction(disconnectAction(context, role))
            }
            VoiceChatState.OFFERING -> {
                val person = Person.Builder().setName(peerName).build()
                builder
                    .setContentText("Calling $peerName...")
                    .addPerson(person)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setStyle(
                        NotificationCompat.CallStyle.forOngoingCall(
                            person,
                            actionPendingIntent(
                                context, role, NotificationActionReceiver.ACTION_CANCEL_OFFER, REQUEST_CODE_CANCEL_OFFER,
                            ),
                        ),
                    )
                    .addAction(disconnectAction(context, role))
            }
            VoiceChatState.INCOMING_OFFER -> {
                val person = Person.Builder().setName(peerName).build()
                builder
                    .setContentText("$peerName wants to start voice chat")
                    .addPerson(person)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setStyle(
                        NotificationCompat.CallStyle.forIncomingCall(
                            person,
                            actionPendingIntent(context, role, NotificationActionReceiver.ACTION_DECLINE, REQUEST_CODE_DECLINE),
                            actionPendingIntent(context, role, NotificationActionReceiver.ACTION_ACCEPT, REQUEST_CODE_ACCEPT),
                        ),
                    )
                    .addAction(disconnectAction(context, role))
            }
            VoiceChatState.ACTIVE -> {
                val person = Person.Builder().setName(peerName).build()
                builder
                    .setContentText("Voice chat active with $peerName")
                    .addPerson(person)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setStyle(
                        NotificationCompat.CallStyle.forOngoingCall(
                            person,
                            actionPendingIntent(context, role, NotificationActionReceiver.ACTION_END_CALL, REQUEST_CODE_END_CALL),
                        ),
                    )
                    .addAction(
                        action(
                            context, role, NotificationActionReceiver.ACTION_TOGGLE_MUTE,
                            if (isMuted) "Unmute" else "Mute", REQUEST_CODE_TOGGLE_MUTE,
                        ),
                    )
                    .addAction(disconnectAction(context, role))
            }
        }

        return builder.build()
    }

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE_CONTENT,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun disconnectAction(context: Context, role: ConnectionRole): NotificationCompat.Action =
        action(context, role, NotificationActionReceiver.ACTION_DISCONNECT, "Disconnect", REQUEST_CODE_DISCONNECT)

    private fun action(
        context: Context,
        role: ConnectionRole,
        actionName: String,
        title: String,
        requestCodeBase: Int,
    ): NotificationCompat.Action = NotificationCompat.Action.Builder(
        R.drawable.ic_notification_rider,
        title,
        actionPendingIntent(context, role, actionName, requestCodeBase),
    ).build()

    private fun actionPendingIntent(
        context: Context,
        role: ConnectionRole,
        actionName: String,
        requestCodeBase: Int,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = actionName
            putExtra(NotificationActionReceiver.EXTRA_ROLE, role.name)
        }
        // Distinct per role so Rider's and Pillion's action PendingIntents
        // never collide -- filterEquals() ignores extras, so the role alone
        // wouldn't be enough to distinguish them without this.
        val requestCode = requestCodeBase + if (role == ConnectionRole.RIDER) 0 else 100
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val REQUEST_CODE_CONTENT = 0
    private const val REQUEST_CODE_OFFER = 0
    private const val REQUEST_CODE_CANCEL_OFFER = 1
    private const val REQUEST_CODE_ACCEPT = 2
    private const val REQUEST_CODE_DECLINE = 3
    private const val REQUEST_CODE_TOGGLE_MUTE = 4
    private const val REQUEST_CODE_END_CALL = 5
    private const val REQUEST_CODE_DISCONNECT = 6
}
