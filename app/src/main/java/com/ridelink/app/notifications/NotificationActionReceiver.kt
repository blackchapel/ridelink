package com.ridelink.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ridelink.app.pillion.PillionForegroundService
import com.ridelink.app.pillion.PillionSession
import com.ridelink.app.rider.RiderForegroundService
import com.ridelink.app.rider.RiderSession

/**
 * Handles taps on the connected-status notification's action buttons.
 * Manifest-registered (not runtime) so these fire reliably whether or not
 * any Activity is currently alive -- every PendingIntent targets this
 * receiver explicitly (see ConnectionNotificationBuilder), so no
 * <intent-filter> is needed and no implicit-broadcast restriction applies.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val role = intent.getStringExtra(EXTRA_ROLE)?.let { runCatching { ConnectionRole.valueOf(it) }.getOrNull() }
            ?: return
        val voiceChat = when (role) {
            ConnectionRole.RIDER -> RiderSession.voiceChat
            ConnectionRole.PILLION -> PillionSession.voiceChat
        }

        when (intent.action) {
            ACTION_OFFER -> voiceChat?.offer()
            ACTION_CANCEL_OFFER -> voiceChat?.cancelOffer()
            ACTION_ACCEPT -> voiceChat?.accept()
            ACTION_DECLINE -> voiceChat?.decline()
            ACTION_TOGGLE_MUTE -> voiceChat?.let { it.setMuted(!it.isMuted.value) }
            ACTION_END_CALL -> voiceChat?.end()
            ACTION_DISCONNECT -> {
                // Not a bare reset() -- that would leave the service running,
                // which would just repost the quiet "Listening..." notification
                // a moment later instead of making it go away. The stop-intent
                // does reset() *and* cancels the notification *and* stops the
                // service, exactly like the in-app End button already does.
                val stopIntent = when (role) {
                    ConnectionRole.RIDER -> RiderForegroundService.stopIntent(context)
                    ConnectionRole.PILLION -> PillionForegroundService.stopIntent(context)
                }
                ContextCompat.startForegroundService(context, stopIntent)
            }
        }
    }

    companion object {
        const val EXTRA_ROLE = "com.ridelink.app.notifications.EXTRA_ROLE"
        const val ACTION_OFFER = "com.ridelink.app.notifications.ACTION_OFFER"
        const val ACTION_CANCEL_OFFER = "com.ridelink.app.notifications.ACTION_CANCEL_OFFER"
        const val ACTION_ACCEPT = "com.ridelink.app.notifications.ACTION_ACCEPT"
        const val ACTION_DECLINE = "com.ridelink.app.notifications.ACTION_DECLINE"
        const val ACTION_TOGGLE_MUTE = "com.ridelink.app.notifications.ACTION_TOGGLE_MUTE"
        const val ACTION_END_CALL = "com.ridelink.app.notifications.ACTION_END_CALL"
        const val ACTION_DISCONNECT = "com.ridelink.app.notifications.ACTION_DISCONNECT"
    }
}
