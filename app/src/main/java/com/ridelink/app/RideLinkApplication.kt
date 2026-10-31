package com.ridelink.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class RideLinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)

        // Quiet, ongoing "service is alive" status -- required by Android's
        // foreground-service policy, deliberately low-key.
        manager.createNotificationChannel(
            NotificationChannel(
                RIDER_STATUS_CHANNEL_ID,
                "Rider listening status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows whether RideLink is listening for a route from your pillion."
                setShowBadge(false)
            },
        )

        // A new route is a real event worth surfacing -- HIGH so it actually
        // alerts (heads-up) instead of silently sitting in the shade like the
        // status channel above. Notification.setPriority() is ignored on
        // API 26+; only the channel's importance controls this.
        manager.createNotificationChannel(
            NotificationChannel(
                RIDER_ROUTE_CHANNEL_ID,
                "New route received",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts you when your pillion shares a new route."
            },
        )

        // Quiet, ongoing "call is active" status for VoiceCallForegroundService --
        // same reasoning as the status channel above.
        manager.createNotificationChannel(
            NotificationChannel(
                VOICE_CALL_CHANNEL_ID,
                "Voice chat status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when a RideLink voice chat call is active."
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val RIDER_STATUS_CHANNEL_ID = "rider_listening"
        const val RIDER_ROUTE_CHANNEL_ID = "rider_route_received"
        const val VOICE_CALL_CHANNEL_ID = "voice_call_active"
    }
}
