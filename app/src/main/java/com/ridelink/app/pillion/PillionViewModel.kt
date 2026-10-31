package com.ridelink.app.pillion

import android.app.Application
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
 * torn down while the app is backgrounded.
 */
object PillionSession {
    private var manager: NearbyManager? = null

    var voiceChat: VoiceChatSession? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _capturedLink = MutableStateFlow<String?>(null)
    val capturedLink: StateFlow<String?> = _capturedLink.asStateFlow()

    // Tracks the last link actually sent, not just "have we ever sent
    // anything" -- a boolean would permanently block every share after the
    // first one, since a later share captures a *new* link that was never
    // sent yet.
    private val _lastSentLink = MutableStateFlow<String?>(null)
    val lastSentLink: StateFlow<String?> = _lastSentLink.asStateFlow()

    fun manager(context: android.content.Context): NearbyManager {
        val existing = manager
        if (existing != null) return existing

        val appContext = context.applicationContext
        val created = NearbyManager(appContext)
        manager = created
        voiceChat = VoiceChatSession(appContext, created)
        scope.launch {
            combine(created.state, _capturedLink) { state, link -> state to link }
                .collect { (state, link) ->
                    if (state is NearbyState.Connected && link != null && link != _lastSentLink.value) {
                        created.send(ControlMessage.RouteLink(link))
                        _lastSentLink.value = link
                    }
                }
        }
        return created
    }

    fun onLinkCaptured(link: String) {
        _capturedLink.value = link
    }

    fun reset() {
        voiceChat?.shutdown()
        voiceChat = null
        manager?.stop()
        manager = null
        _capturedLink.value = null
        _lastSentLink.value = null
    }
}

class PillionViewModel(application: Application) : AndroidViewModel(application) {

    private val nearbyManager get() = PillionSession.manager(getApplication())

    val connectionState: StateFlow<NearbyState> get() = nearbyManager.state
    val capturedLink: StateFlow<String?> = PillionSession.capturedLink
    val lastSentLink: StateFlow<String?> = PillionSession.lastSentLink
    val voiceChat: VoiceChatSession? get() = PillionSession.voiceChat

    fun start() {
        nearbyManager.startAdvertising()
    }

    fun stop() {
        PillionSession.reset()
    }
}
