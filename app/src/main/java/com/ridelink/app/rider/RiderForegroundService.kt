package com.ridelink.app.rider

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.ridelink.app.EXTRA_ROUTE_LINK
import com.ridelink.app.MainActivity
import com.ridelink.app.R
import com.ridelink.app.RideLinkApplication
import com.ridelink.app.nearby.NearbyState
import com.ridelink.app.nearby.formatPeerName
import com.ridelink.app.notifications.ConnectionNotificationBuilder
import com.ridelink.app.notifications.ConnectionNotificationHost
import com.ridelink.app.notifications.ConnectionRole
import com.ridelink.app.voicechat.VoiceChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Keeps [RiderSession]'s Nearby Connections session alive while RideLink
 * itself is backgrounded -- the whole point of this app is that the rider
 * never needs to look at it again after tapping Start. Android requires a
 * visible ongoing notification for any foreground service (that's an OS
 * policy, not something this app can opt out of while still surviving in
 * the background), so this posts two distinct notifications:
 *
 * - The single persistent connected-status notification (see
 *   ConnectionNotificationBuilder for its full per-state table): quiet
 *   while not yet paired, high-priority with voice chat action buttons once
 *   connected. Also implements [ConnectionNotificationHost] so
 *   VoiceChatSession can add/remove this service's "microphone"
 *   foreground-service type for the duration of a call -- there's still
 *   exactly one notification and one service during a call, not a second
 *   one layered on top.
 * - A separate, actually-alerting one-shot notification each time a new
 *   route arrives, whose tap opens MainActivity (not Maps directly) --
 *   launching your own app from a notification tap has no
 *   background-activity-start ambiguity on any Android version/OEM skin,
 *   and MainActivity immediately relaunches Maps once it's genuinely in
 *   the foreground, where that same restriction no longer applies either.
 *   The fully-automatic direct launch (no tap) is still attempted too --
 *   free when it works -- but this notification is the guaranteed path.
 */
class RiderForegroundService : Service(), ConnectionNotificationHost {

    private var serviceScope: CoroutineScope? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_LISTENING) {
            stopListening()
        } else {
            startListening()
        }
        return START_NOT_STICKY
    }

    private fun startListening() {
        if (serviceScope != null) return // already running -- re-Start is a no-op

        ServiceCompat.startForeground(
            this,
            STATUS_NOTIFICATION_ID,
            ConnectionNotificationBuilder.build(
                this, ConnectionRole.RIDER, NearbyState.Idle, VoiceChatState.IDLE, isMuted = false, alert = false,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        RiderSession.start(applicationContext)
        ConnectionNotificationHost.current = this

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope
        val voiceChat = RiderSession.voiceChat
        if (voiceChat != null) {
            scope.launch {
                // Only re-alert on the two moments actually worth interrupting
                // for: just paired, and an incoming call -- every other update
                // (offering, active, mute toggling) posts quietly.
                var wasConnected = false
                var wasIncomingOffer = false
                combine(
                    RiderSession.connectionState,
                    voiceChat.callState,
                    voiceChat.isMuted,
                ) { nearbyState, callState, muted -> Triple(nearbyState, callState, muted) }
                    .collect { (nearbyState, callState, muted) ->
                        val nowConnected = nearbyState is NearbyState.Connected
                        val nowIncomingOffer = callState == VoiceChatState.INCOMING_OFFER
                        val alert = (nowConnected && !wasConnected) || (nowIncomingOffer && !wasIncomingOffer)
                        NotificationManagerCompat.from(this@RiderForegroundService).notify(
                            STATUS_NOTIFICATION_ID,
                            ConnectionNotificationBuilder.build(
                                this@RiderForegroundService, ConnectionRole.RIDER, nearbyState, callState, muted, alert,
                            ),
                        )
                        wasConnected = nowConnected
                        wasIncomingOffer = nowIncomingOffer
                    }
            }
        }
        scope.launch {
            // Only fires on a genuinely new link (StateFlow conflates equal
            // consecutive values) -- never re-alerts for unrelated
            // connection-state changes.
            RiderSession.latestRouteLink.filterNotNull().collect { link ->
                val peerName = (RiderSession.connectionState.value as? NearbyState.Connected)
                    ?.let { formatPeerName(it.endpointName) }
                NotificationManagerCompat.from(this@RiderForegroundService)
                    .notify(ROUTE_NOTIFICATION_ID, buildRouteNotification(link, peerName))
            }
        }
    }

    private fun stopListening() {
        RiderSession.reset()
        if (ConnectionNotificationHost.current === this) ConnectionNotificationHost.current = null
        serviceScope?.cancel()
        serviceScope = null
        NotificationManagerCompat.from(this).apply {
            cancel(STATUS_NOTIFICATION_ID)
            cancel(ROUTE_NOTIFICATION_ID)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        RiderSession.reset()
        if (ConnectionNotificationHost.current === this) ConnectionNotificationHost.current = null
        serviceScope?.cancel()
        serviceScope = null
        NotificationManagerCompat.from(this).apply {
            cancel(STATUS_NOTIFICATION_ID)
            cancel(ROUTE_NOTIFICATION_ID)
        }
        super.onDestroy()
    }

    override fun enterCallType() {
        ServiceCompat.startForeground(
            this,
            STATUS_NOTIFICATION_ID,
            ConnectionNotificationBuilder.build(
                this,
                ConnectionRole.RIDER,
                RiderSession.connectionState.value,
                VoiceChatState.ACTIVE,
                RiderSession.voiceChat?.isMuted?.value ?: false,
                alert = false,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    override fun exitCallType() {
        ServiceCompat.startForeground(
            this,
            STATUS_NOTIFICATION_ID,
            ConnectionNotificationBuilder.build(
                this, ConnectionRole.RIDER, RiderSession.connectionState.value,
                VoiceChatState.IDLE, isMuted = false, alert = false,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun buildRouteNotification(link: String, peerName: String?): Notification {
        val text = if (peerName != null) {
            "New route from $peerName -- tap to open in Google Maps"
        } else {
            "New route received -- tap to open in Google Maps"
        }

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_ROUTE_LINK, link)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_ROUTE,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, RideLinkApplication.RIDER_ROUTE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_rider)
            .setContentTitle("RideLink")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val STATUS_NOTIFICATION_ID = 1001
        private const val ROUTE_NOTIFICATION_ID = 1002
        private const val REQUEST_CODE_ROUTE = 1
        private const val ACTION_STOP_LISTENING = "com.ridelink.app.rider.STOP_LISTENING"

        fun startIntent(context: Context): Intent =
            Intent(context, RiderForegroundService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, RiderForegroundService::class.java).setAction(ACTION_STOP_LISTENING)
    }
}
