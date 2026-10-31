package com.ridelink.app.pillion

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.ridelink.app.crashGuard
import com.ridelink.app.nearby.NearbyState
import com.ridelink.app.notifications.ConnectionNotificationBuilder
import com.ridelink.app.notifications.ConnectionNotificationHost
import com.ridelink.app.notifications.ConnectionRole
import com.ridelink.app.notifications.launchConnectionNotificationUpdates
import com.ridelink.app.voicechat.VoiceChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        startListening()
        return START_NOT_STICKY
    }

    private fun startListening() {
        if (serviceScope != null) return // already running -- re-Start is a no-op

        try {
            ServiceCompat.startForeground(
                this,
                STATUS_NOTIFICATION_ID,
                ConnectionNotificationBuilder.build(
                    this, ConnectionRole.PILLION, NearbyState.Idle, VoiceChatState.IDLE, null, alert = false,
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // Android refused to make this a foreground service (missing
            // Bluetooth/Nearby permission, or started from a state where that
            // is not allowed). Without this the app would crash.
            Log.e(TAG, "Could not start foreground service", e)
            Toast.makeText(this, "RideLink couldn't start in the background. Check its permissions.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        PillionSession.start(applicationContext)
        ConnectionNotificationHost.current = this

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard(TAG))
        serviceScope = scope
        PillionSession.voiceChat?.let { voiceChat ->
            scope.launchConnectionNotificationUpdates(
                this, ConnectionRole.PILLION, STATUS_NOTIFICATION_ID, PillionSession.connectionState, voiceChat,
            )
        }
    }

    override fun stopListening() {
        // Unregister first: resetting the session ends any call, which would
        // otherwise try to change this (now stopping) service's foreground type.
        if (ConnectionNotificationHost.current === this) ConnectionNotificationHost.current = null
        PillionSession.reset()
        serviceScope?.cancel()
        serviceScope = null
        NotificationManagerCompat.from(this).cancel(STATUS_NOTIFICATION_ID)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (ConnectionNotificationHost.current === this) ConnectionNotificationHost.current = null
        PillionSession.reset()
        serviceScope?.cancel()
        serviceScope = null
        NotificationManagerCompat.from(this).cancel(STATUS_NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun enterCallType(): Boolean = changeForegroundType(withMicrophone = true)

    override fun exitCallType() {
        changeForegroundType(withMicrophone = false)
    }

    // False if Android refused (no microphone permission, or the app is in a
    // state where a microphone service may not start). Never throws.
    private fun changeForegroundType(withMicrophone: Boolean): Boolean = try {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (withMicrophone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(
            this,
            STATUS_NOTIFICATION_ID,
            ConnectionNotificationBuilder.build(
                this, ConnectionRole.PILLION, PillionSession.connectionState.value,
                PillionSession.voiceChat?.callState?.value ?: VoiceChatState.IDLE, PillionSession.voiceChat, alert = false,
            ),
            type,
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not change foreground service type (microphone=$withMicrophone)", e)
        false
    }

    companion object {
        private const val STATUS_NOTIFICATION_ID = 1003
        private const val TAG = "PillionService"

        fun startIntent(context: Context): Intent =
            Intent(context, PillionForegroundService::class.java)
    }
}
