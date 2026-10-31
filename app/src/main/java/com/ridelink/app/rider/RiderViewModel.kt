package com.ridelink.app.rider

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.ridelink.app.nearby.NearbyState
import com.ridelink.app.notifications.ConnectionRole
import com.ridelink.app.notifications.stopConnection
import com.ridelink.app.voicechat.VoiceChatSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin pass-through over [RiderSession] / [RiderForegroundService] (same
 * spirit as PillionViewModel over PillionSession) -- this ViewModel owns no
 * Nearby Connections state itself, only routes Start/End to the service.
 */
class RiderViewModel(application: Application) : AndroidViewModel(application) {

    val connectionState: StateFlow<NearbyState> = RiderSession.connectionState
    val lastRouteOpenedAt: StateFlow<Long?> = RiderSession.lastRouteOpenedAt
    val isActive: StateFlow<Boolean> = RiderSession.isActive
    val voiceChat: VoiceChatSession? get() = RiderSession.voiceChat

    fun start() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, RiderForegroundService.startIntent(context))
    }

    fun stop() {
        stopConnection(ConnectionRole.RIDER)
    }
}
