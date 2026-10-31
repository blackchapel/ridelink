package com.ridelink.app.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.os.bundleOf
import com.ridelink.app.MainActivity
import com.ridelink.app.R
import com.ridelink.app.RideLinkApplication
import com.ridelink.app.nearby.NearbyState
import com.ridelink.app.nearby.formatPeerName
import com.ridelink.app.voicechat.AudioOutputOption
import com.ridelink.app.voicechat.VoiceChatSession
import com.ridelink.app.voicechat.VoiceChatState

/**
 * Builds the single persistent notification for a role (Rider or Pillion):
 * a quiet, low-priority status while not yet connected, and -- once
 * connected -- a high-priority notification whose buttons track
 * [VoiceChatState]:
 *
 * - connected:     [Disconnect]
 * - we're calling: [Cancel]
 * - incoming:      [Reject] [Accept]
 * - in call:       [Mute/Unmute] [<current output>] [End]
 *
 * The connected notification is also marked as an Android "Live Update" so
 * phones that support it (Samsung's Now Bar / status-bar pill) can promote
 * it. It stays a plain notification with plain buttons otherwise: no custom
 * layout and no CallStyle, so it looks and behaves the same everywhere
 * (buttons appear when it is expanded) and stays eligible for promotion.
 *
 * Stateless: callers decide [alert] (whether this specific update should make
 * a sound/heads-up rather than update quietly) -- see
 * [launchConnectionNotificationUpdates].
 */
object ConnectionNotificationBuilder {

    fun build(
        context: Context,
        role: ConnectionRole,
        nearbyState: NearbyState,
        callState: VoiceChatState,
        voiceChat: VoiceChatSession?,
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
            context, role, formatPeerName(nearbyState.endpointName), callState, voiceChat, alert,
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
        voiceChat: VoiceChatSession?,
        alert: Boolean,
    ): Notification {
        val peerRole = when (role) {
            ConnectionRole.RIDER -> "Pillion"
            ConnectionRole.PILLION -> "Rider"
        }
        val isMuted = voiceChat?.isMuted?.value ?: false
        val output = voiceChat?.routing?.selected?.value ?: AudioOutputOption.EARPIECE
        val callStartedAt = voiceChat?.callStartedAt?.value

        // `chip` is the short text for the status-bar pill. It has to stay
        // under 7 characters or Android may fall back to icon-only; the
        // notification's own title and text carry the full wording. During an
        // unmuted call there is no chip text: the pill shows the call timer.
        val spec = when (callState) {
            VoiceChatState.IDLE -> Spec(
                title = "Connected",
                text = "$peerRole · $peerName",
                chip = "Linked",
                actions = listOf(disconnectAction(context, role)),
            )
            VoiceChatState.OFFERING -> Spec(
                title = "Calling $peerRole",
                text = "$peerRole · $peerName",
                chip = "Call",
                actions = listOf(
                    action(context, role, NotificationActionReceiver.ACTION_CANCEL_OFFER, "Cancel", REQUEST_CODE_CANCEL_OFFER),
                ),
            )
            VoiceChatState.INCOMING_OFFER -> Spec(
                title = "Voice chat request",
                text = "$peerRole wants to talk",
                chip = "Voice",
                actions = listOf(
                    action(context, role, NotificationActionReceiver.ACTION_DECLINE, "Reject", REQUEST_CODE_DECLINE),
                    action(context, role, NotificationActionReceiver.ACTION_ACCEPT, "Accept", REQUEST_CODE_ACCEPT),
                ),
            )
            VoiceChatState.ACTIVE -> Spec(
                title = "Voice chat",
                text = "$peerRole · ${output.label()}" + if (isMuted) " · You are muted" else "",
                chip = if (isMuted) "Muted" else null,
                actions = listOf(
                    action(
                        context, role, NotificationActionReceiver.ACTION_TOGGLE_MUTE,
                        if (isMuted) "Unmute" else "Mute", REQUEST_CODE_TOGGLE_MUTE,
                    ),
                    action(context, role, NotificationActionReceiver.ACTION_CYCLE_OUTPUT, output.label(), REQUEST_CODE_CYCLE_OUTPUT),
                    action(context, role, NotificationActionReceiver.ACTION_END_CALL, "End", REQUEST_CODE_END_CALL),
                ),
            )
        }

        val builder = NotificationCompat.Builder(context, RideLinkApplication.CONNECTION_ACTIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_rider)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent(context))
        spec.actions.forEach(builder::addAction)

        if (callState == VoiceChatState.ACTIVE && callStartedAt != null) {
            // A chronometer ticks on its own, so the notification is not
            // re-posted every second (that would undo the battery work).
            builder.setWhen(callStartedAt).setShowWhen(true).setUsesChronometer(true)
        }

        // Android 16+ Live Update: ask to be promoted to the status-bar chip
        // (and, on Samsung, the Now Bar). Harmless where unsupported.
        builder.setRequestPromotedOngoing(true)
        spec.chip?.let { builder.setShortCriticalText(it) }

        builder.addExtras(samsungExtras(spec.title, spec.text, spec.chip))
        return builder.build()
    }

    /**
     * EXPERIMENT: Samsung's own (One UI 7 era) opt-in for Live Notifications and
     * the Now Bar. These keys come from unofficial developer blogs, not Samsung
     * documentation, and Samsung reportedly honors them only for allowed apps.
     * Phones that do not know them simply ignore the extras.
     */
    private fun samsungExtras(primary: String, secondary: String, chip: String?): Bundle {
        val extras = bundleOf(
            "android.ongoingActivityNoti.style" to 1,
            "android.ongoingActivityNoti.primaryInfo" to primary,
            "android.ongoingActivityNoti.secondaryInfo" to secondary,
            "android.ongoingActivityNoti.actionType" to 1,
            "android.ongoingActivityNoti.actionPrimarySet" to 0,
            "android.ongoingActivityNoti.nowbarPrimaryInfo" to primary,
            "android.ongoingActivityNoti.nowbarSecondaryInfo" to secondary,
        )
        if (chip != null) extras.putString("android.ongoingActivityNoti.chipExpandedText", chip)
        return extras
    }

    private class Spec(
        val title: String,
        val text: String,
        val chip: String?,
        val actions: List<NotificationCompat.Action>,
    )

    private fun AudioOutputOption.label(): String = when (this) {
        AudioOutputOption.SPEAKER -> "Speaker"
        AudioOutputOption.EARPIECE -> "Call speaker"
        AudioOutputOption.BLUETOOTH -> "Bluetooth"
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
    private const val REQUEST_CODE_CANCEL_OFFER = 1
    private const val REQUEST_CODE_ACCEPT = 2
    private const val REQUEST_CODE_DECLINE = 3
    private const val REQUEST_CODE_TOGGLE_MUTE = 4
    private const val REQUEST_CODE_END_CALL = 5
    private const val REQUEST_CODE_DISCONNECT = 6
    private const val REQUEST_CODE_CYCLE_OUTPUT = 7
}
