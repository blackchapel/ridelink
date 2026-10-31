package com.ridelink.app.voicechat

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ridelink.app.R
import com.ridelink.app.RideLinkApplication

/**
 * Minimal foreground service held only for the duration of an active voice
 * chat call -- keeps the process alive and exempt from background execution
 * limits so mic capture/playback keep running if the app is backgrounded
 * mid-call. Deliberately separate from RiderForegroundService (which stays
 * connectedDevice-typed only, untouched by voice chat): dynamically adding
 * a foreground-service type to an already-running service is not something
 * this app can verify works reliably across Android versions without a
 * real device, so route-listening and call-audio each get their own
 * independently-typed service instead. Shared by both roles.
 */
class VoiceCallForegroundService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification =
            NotificationCompat.Builder(this, RideLinkApplication.VOICE_CALL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_rider)
                .setContentTitle("RideLink")
                .setContentText("Voice chat active")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceCallForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceCallForegroundService::class.java))
        }
    }
}
