package com.ridelink.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ridelink.app.CrashLog
import com.ridelink.app.pillion.PillionSession
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
        // A failure here (e.g. a call that can't start) must not take the app down.
        try {
            handle(context, intent)
        } catch (e: Exception) {
            Log.e("NotificationAction", "Action ${intent.action} failed", e)
            CrashLog.record("Notification action ${intent.action}", e)
        }
    }

    private fun handle(context: Context, intent: Intent) {
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
            ACTION_CYCLE_OUTPUT -> voiceChat?.routing?.cycleToNext()
            ACTION_END_CALL -> voiceChat?.end()
            // Not a bare reset() -- that would leave the service running, which
            // would just repost the quiet "Listening..." notification a moment
            // later. This resets the session *and* removes the notification
            // *and* stops the service, exactly like the in-app End button.
            ACTION_DISCONNECT -> stopConnection(role)
        }
    }

    companion object {
        const val EXTRA_ROLE = "com.ridelink.app.notifications.EXTRA_ROLE"
        const val ACTION_OFFER = "com.ridelink.app.notifications.ACTION_OFFER"
        const val ACTION_CANCEL_OFFER = "com.ridelink.app.notifications.ACTION_CANCEL_OFFER"
        const val ACTION_ACCEPT = "com.ridelink.app.notifications.ACTION_ACCEPT"
        const val ACTION_DECLINE = "com.ridelink.app.notifications.ACTION_DECLINE"
        const val ACTION_TOGGLE_MUTE = "com.ridelink.app.notifications.ACTION_TOGGLE_MUTE"
        const val ACTION_CYCLE_OUTPUT = "com.ridelink.app.notifications.ACTION_CYCLE_OUTPUT"
        const val ACTION_END_CALL = "com.ridelink.app.notifications.ACTION_END_CALL"
        const val ACTION_DISCONNECT = "com.ridelink.app.notifications.ACTION_DISCONNECT"
    }
}
