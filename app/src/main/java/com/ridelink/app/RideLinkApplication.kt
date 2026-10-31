package com.ridelink.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class RideLinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        AppVisibility.init()
        val manager = getSystemService(NotificationManager::class.java)

        // Quiet, ongoing "not yet connected" status per role -- required by
        // Android's foreground-service policy while advertising/discovering,
        // deliberately low-key since there's nothing to act on yet.
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
        manager.createNotificationChannel(
            NotificationChannel(
                PILLION_STATUS_CHANNEL_ID,
                "Pillion connection status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows whether RideLink is waiting for your rider to connect."
                setShowBadge(false)
            },
        )

        // A new route is a real event worth surfacing -- HIGH so it actually
        // alerts (heads-up) instead of silently sitting in the shade like the
        // status channels above. Notification.setPriority() is ignored on
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

        // Once paired, this is the primary way to control voice chat without
        // opening the app. Two channels, because a channel's importance (not a
        // per-notification flag) decides whether a heads-up appears, and some
        // phones pop one up the first time a notification lands on a HIGH
        // channel even with "only alert once" set:
        // - ALERT (HIGH): used only for the moments worth interrupting for --
        //   just paired and an incoming voice request -- while the app is in
        //   the background.
        // - QUIET (LOW): the same notification the rest of the time, including
        //   everything that happens while the app is open.
        // Shared by both roles: only one role ever runs per install.
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_ACTIVE_CHANNEL_ID,
                "Pairing and voice chat alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts you when your phone pairs or the other phone asks for a voice chat while RideLink is in the background."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_QUIET_CHANNEL_ID,
                "Connected -- controls",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows once paired, with call, disconnect and voice chat controls (accept, reject, mute, output, end). Silent."
                setShowBadge(false)
            },
        )

        // Superseded by CONNECTION_ACTIVE_CHANNEL_ID above -- deleted (not
        // just stopped-creating) so existing installs don't keep an orphaned
        // channel around from the old separate "call active" notification.
        manager.deleteNotificationChannel(LEGACY_VOICE_CALL_CHANNEL_ID)
    }

    companion object {
        const val RIDER_STATUS_CHANNEL_ID = "rider_listening"
        const val PILLION_STATUS_CHANNEL_ID = "pillion_listening"
        const val RIDER_ROUTE_CHANNEL_ID = "rider_route_received"
        const val CONNECTION_ACTIVE_CHANNEL_ID = "connection_active"
        const val CONNECTION_QUIET_CHANNEL_ID = "connection_quiet"
        private const val LEGACY_VOICE_CALL_CHANNEL_ID = "voice_call_active"
    }
}
