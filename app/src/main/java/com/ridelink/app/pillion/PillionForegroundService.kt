package com.ridelink.app.pillion

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.ridelink.app.nearby.NearbyState
import com.ridelink.app.notifications.ConnectionNotificationBuilder
import com.ridelink.app.notifications.ConnectionNotificationHost
import com.ridelink.app.notifications.ConnectionRole
import com.ridelink.app.voicechat.VoiceChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Mirrors [com.ridelink.app.rider.RiderForegroundService] for the Pillion
 * role: keeps [PillionSession]'s Nearby Connections session (and any
 * in-progress voice chat) alive while RideLink is backgrounded, and hosts
 * the single persistent connected-status notification for this role. See
 * ConnectionNotificationBuilder for the full per-state table, and
 * RiderForegroundService's doc comment for why this also implements
 * [ConnectionNotificationHost].
 */
class PillionForegroundService : Service(), ConnectionNotificationHost {

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
                this, ConnectionRole.PILLION, NearbyState.Idle, VoiceChatState.IDLE, isMuted = false, alert = false,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        PillionSession.start(applicationContext)
        ConnectionNotificationHost.current = this

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope
        val voiceChat = PillionSession.voiceChat
        if (voiceChat != null) {
            scope.launch {
                var wasConnected = false
                var wasIncomingOffer = false
                combine(
                    PillionSession.connectionState,
                    voiceChat.callState,
                    voiceChat.isMuted,
                ) { nearbyState, callState, muted -> Triple(nearbyState, callState, muted) }
                    .collect { (nearbyState, callState, muted) ->
                        val nowConnected = nearbyState is NearbyState.Connected
                        val nowIncomingOffer = callState == VoiceChatState.INCOMING_OFFER
                        val alert = (nowConnected && !wasConnected) || (nowIncomingOffer && !wasIncomingOffer)
                        NotificationManagerCompat.from(this@PillionForegroundService).notify(
                            STATUS_NOTIFICATION_ID,
                            ConnectionNotificationBuilder.build(
                                this@PillionForegroundService, ConnectionRole.PILLION, nearbyState, callState, muted, alert,
                            ),
                        )
                        wasConnected = nowConnected
                        wasIncomingOffer = nowIncomingOffer
                    }
            }
        }
    }

    private fun stopListening() {
        PillionSession.reset()
        if (ConnectionNotificationHost.current === this) ConnectionNotificationHost.current = null
        serviceScope?.cancel()
        serviceScope = null
        NotificationManagerCompat.from(this).cancel(STATUS_NOTIFICATION_ID)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        PillionSession.reset()
        if (ConnectionNotificationHost.current === this) ConnectionNotificationHost.current = null
        serviceScope?.cancel()
        serviceScope = null
        NotificationManagerCompat.from(this).cancel(STATUS_NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun enterCallType() {
        ServiceCompat.startForeground(
            this,
            STATUS_NOTIFICATION_ID,
            ConnectionNotificationBuilder.build(
                this,
                ConnectionRole.PILLION,
                PillionSession.connectionState.value,
                VoiceChatState.ACTIVE,
                PillionSession.voiceChat?.isMuted?.value ?: false,
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
                this, ConnectionRole.PILLION, PillionSession.connectionState.value,
                VoiceChatState.IDLE, isMuted = false, alert = false,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    companion object {
        private const val STATUS_NOTIFICATION_ID = 1003
        private const val ACTION_STOP_LISTENING = "com.ridelink.app.pillion.STOP_LISTENING"

        fun startIntent(context: Context): Intent =
            Intent(context, PillionForegroundService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, PillionForegroundService::class.java).setAction(ACTION_STOP_LISTENING)
    }
}
