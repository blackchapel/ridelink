package com.ridelink.app.pillion

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.ridelink.app.nearby.ControlMessage
import com.ridelink.app.nearby.NearbyManager
import com.ridelink.app.nearby.NearbyState
import com.ridelink.app.voicechat.VoiceChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Application-scoped so a captured share survives the pillion switching away
 * to Google Maps and back -- an Activity/ViewModel-scoped instance could be
 * torn down while the app is backgrounded. Driven by
 * [PillionForegroundService] (mirrors RiderSession/RiderForegroundService),
 * which is what keeps this process alive while Pillion is backgrounded too
 * -- needed so an in-progress voice chat call survives it, not just route
 * sharing.
 */
object PillionSession {
    private var manager: NearbyManager? = null

    var voiceChat: VoiceChatSession? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _connectionState = MutableStateFlow<NearbyState>(NearbyState.Idle)
    val connectionState: StateFlow<NearbyState> = _connectionState.asStateFlow()

    private val _capturedLink = MutableStateFlow<String?>(null)
    val capturedLink: StateFlow<String?> = _capturedLink.asStateFlow()

    // Tracks the last link actually sent, not just "have we ever sent
    // anything" -- a boolean would permanently block every share after the
    // first one, since a later share captures a *new* link that was never
    // sent yet.
    private val _lastSentLink = MutableStateFlow<String?>(null)
    val lastSentLink: StateFlow<String?> = _lastSentLink.asStateFlow()

    fun start(context: Context) {
        if (manager != null) return // already advertising -- re-entering the screen is a no-op

        val appContext = context.applicationContext
        val created = NearbyManager(appContext)
        manager = created
        voiceChat = VoiceChatSession(appContext, created)
        _connectionState.value = NearbyState.Idle

        scope.launch {
            created.state.collect { _connectionState.value = it }
        }
        scope.launch {
            combine(created.state, _capturedLink) { state, link -> state to link }
                .collect { (state, link) ->
                    if (state is NearbyState.Connected && link != null && link != _lastSentLink.value) {
                        created.send(ControlMessage.RouteLink(link))
                        _lastSentLink.value = link
                    }
                }
        }
        created.startAdvertising()
    }

    fun onLinkCaptured(link: String) {
        _capturedLink.value = link
    }

    fun reset() {
        voiceChat?.shutdown()
        voiceChat = null
        manager?.stop()
        manager = null
        _connectionState.value = NearbyState.Idle
        _capturedLink.value = null
        _lastSentLink.value = null
    }
}

/**
 * Thin pass-through over [PillionSession] / [PillionForegroundService]
 * (same spirit as RiderViewModel over RiderSession) -- this ViewModel owns
 * no Nearby Connections state itself, only routes Start/End to the service.
 */
class PillionViewModel(application: Application) : AndroidViewModel(application) {

    val connectionState: StateFlow<NearbyState> = PillionSession.connectionState
    val capturedLink: StateFlow<String?> = PillionSession.capturedLink
    val lastSentLink: StateFlow<String?> = PillionSession.lastSentLink
    val voiceChat: VoiceChatSession? get() = PillionSession.voiceChat

    fun start() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, PillionForegroundService.startIntent(context))
    }

    fun stop() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, PillionForegroundService.stopIntent(context))
    }
}
