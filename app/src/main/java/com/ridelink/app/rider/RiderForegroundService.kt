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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * - A quiet ongoing status notification (required, kept minimal).
 * - A separate, actually-alerting one-shot notification each time a new
 *   route arrives, whose tap opens MainActivity (not Maps directly) --
 *   launching your own app from a notification tap has no
 *   background-activity-start ambiguity on any Android version/OEM skin,
 *   and MainActivity immediately relaunches Maps once it's genuinely in
 *   the foreground, where that same restriction no longer applies either.
 *   The fully-automatic direct launch (no tap) is still attempted too --
 *   free when it works -- but this notification is the guaranteed path.
 */
class RiderForegroundService : Service() {

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
            buildStatusNotification(NearbyState.Idle),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        RiderSession.start(applicationContext)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope
        scope.launch {
            RiderSession.connectionState.collect { state ->
                NotificationManagerCompat.from(this@RiderForegroundService)
                    .notify(STATUS_NOTIFICATION_ID, buildStatusNotification(state))
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
        serviceScope?.cancel()
        serviceScope = null
        NotificationManagerCompat.from(this).apply {
            cancel(STATUS_NOTIFICATION_ID)
            cancel(ROUTE_NOTIFICATION_ID)
        }
        super.onDestroy()
    }

    private fun buildStatusNotification(state: NearbyState): Notification {
        val text = when (state) {
            is NearbyState.Idle, is NearbyState.Searching ->
                "Listening for your pillion's route..."
            is NearbyState.Connected ->
                "Connected to ${formatPeerName(state.endpointName)}"
            is NearbyState.Disconnected ->
                "Disconnected -- reopen RideLink to reconnect"
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_STATUS,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, RideLinkApplication.RIDER_STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_rider)
            .setContentTitle("RideLink")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
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
        private const val REQUEST_CODE_STATUS = 0
        private const val REQUEST_CODE_ROUTE = 1
        private const val ACTION_STOP_LISTENING = "com.ridelink.app.rider.STOP_LISTENING"

        fun startIntent(context: Context): Intent =
            Intent(context, RiderForegroundService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, RiderForegroundService::class.java).setAction(ACTION_STOP_LISTENING)
    }
}
