package com.ridelink.app.rider

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.ridelink.app.crashGuard
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
import kotlinx.coroutines.launch

internal const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

/**
 * Application-scoped (mirrors PillionSession) so the Nearby Connections
 * session -- and the ability to keep launching Google Maps for new routes --
 * survives RideLink being backgrounded. Driven by RiderForegroundService,
 * which is what keeps this process alive and exempt from Android's
 * background-activity-start restrictions.
 */
object RiderSession {
    private var manager: NearbyManager? = null

    var voiceChat: VoiceChatSession? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard("RiderSession"))

    // True from start() until reset(). Lets the UI notice the session ended
    // while it was on screen (e.g. Disconnect tapped in the notification).
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _connectionState = MutableStateFlow<NearbyState>(NearbyState.Idle)
    val connectionState: StateFlow<NearbyState> = _connectionState.asStateFlow()

    // Most recently received route -- used both to relaunch Maps and as the
    // notification's tap target, so tapping it always jumps to the latest route.
    private val _latestRouteLink = MutableStateFlow<String?>(null)
    val latestRouteLink: StateFlow<String?> = _latestRouteLink.asStateFlow()

    // A timestamp, not a Boolean: StateFlow conflates equal consecutive
    // values, so a one-shot flag would only ever notify the UI once even
    // though openInGoogleMaps() below runs on every single route.
    private val _lastRouteOpenedAt = MutableStateFlow<Long?>(null)
    val lastRouteOpenedAt: StateFlow<Long?> = _lastRouteOpenedAt.asStateFlow()

    fun start(context: Context) {
        if (manager != null) return // already listening -- re-entering the screen is a no-op

        val appContext = context.applicationContext
        val created = NearbyManager(appContext)
        manager = created
        voiceChat = VoiceChatSession(appContext, created)
        _connectionState.value = NearbyState.Idle
        _latestRouteLink.value = null
        _lastRouteOpenedAt.value = null
        _isActive.value = true

        scope.launch {
            created.state.collect { _connectionState.value = it }
        }
        scope.launch {
            created.receivedMessages.collect { message ->
                if (message is ControlMessage.RouteLink) {
                    // Unconditional -- every route triggers a launch, whether
                    // it's the first of the ride or the fifth.
                    // Fails quietly (false) rather than crash if nothing can open it.
                    openInGoogleMaps(appContext, message.url)
                    _latestRouteLink.value = message.url
                    _lastRouteOpenedAt.value = System.currentTimeMillis()
                }
            }
        }
        created.startDiscovery()
    }

    fun reset() {
        _isActive.value = false
        voiceChat?.shutdown()
        voiceChat = null
        manager?.stop()
        manager = null
        _connectionState.value = NearbyState.Idle
        _latestRouteLink.value = null
        _lastRouteOpenedAt.value = null
    }

    /** True if some app was started for the link; never throws. */
    fun openInGoogleMaps(context: Context, link: String): Boolean {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return false
        val mapsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(GOOGLE_MAPS_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(mapsIntent)
            true
        } catch (e: ActivityNotFoundException) {
            // Google Maps isn't installed under the expected package name --
            // fall back to letting Android resolve the link generally.
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e2: Exception) {
                Log.e("RiderSession", "No app can open the route link", e2)
                false
            }
        } catch (e: Exception) {
            Log.e("RiderSession", "Couldn't open the route link", e)
            false
        }
    }
}
