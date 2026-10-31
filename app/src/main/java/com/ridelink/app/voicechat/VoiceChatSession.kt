package com.ridelink.app.voicechat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ridelink.app.crashGuard
import com.ridelink.app.nearby.AudioChunk
import com.ridelink.app.nearby.ControlMessage
import com.ridelink.app.nearby.NearbyManager
import com.ridelink.app.nearby.NearbyState
import com.ridelink.app.notifications.ConnectionNotificationHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VoiceChatState { IDLE, OFFERING, INCOMING_OFFER, ACTIVE }

/**
 * Owns the voice-chat call lifecycle (offer/accept/decline/end), mute
 * state, and the audio capture/playback pipeline, layered on top of an
 * already-connected NearbyManager. Constructed by whichever role session
 * (Pillion/Rider) is active -- not a standalone global singleton -- so it
 * naturally shares that session's connection lifetime.
 *
 * Mute is purely local: pausing the capture thread's writes is enough to
 * satisfy "stop transmitting" -- the peer's AudioTrack simply stops
 * receiving data, with no need to signal the mute state across the wire.
 */
class VoiceChatSession(private val context: Context, private val nearbyManager: NearbyManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard("VoiceChat"))
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioCapture = AudioCapture()
    private val audioPlayback = AudioPlayback()
    val routing = AudioRoutingController(context)

    private val _callState = MutableStateFlow(VoiceChatState.IDLE)
    val callState: StateFlow<VoiceChatState> = _callState.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    // Wall-clock start of the active call, for the notification's running
    // timer (a chronometer, so the notification isn't re-posted every second).
    private val _callStartedAt = MutableStateFlow<Long?>(null)
    val callStartedAt: StateFlow<Long?> = _callStartedAt.asStateFlow()

    private var focusRequest: AudioFocusRequest? = null

    // The service's "microphone" type must be taken while the user's tap (or
    // the open app) still allows it -- Android refuses it from the background
    // -- so it is taken when a call is offered/accepted and held until the
    // call is over, not when audio finally starts.
    private var holdingMicType = false
    private var audioStarted = false
    private var offerTimeout: Job? = null

    // Next sequence number the receiver will accept -- not a reorder/wait
    // buffer. A chunk older than this is stale and dropped; a chunk newer
    // than expected is played immediately and jumps the pointer forward,
    // treating the gap as skipped rather than something to wait for.
    private var expectedSeq = 0

    init {
        scope.launch {
            nearbyManager.receivedMessages.collect { message ->
                when (message) {
                    ControlMessage.VoiceChatOffer ->
                        when (_callState.value) {
                            VoiceChatState.IDLE -> _callState.value = VoiceChatState.INCOMING_OFFER
                            // Both sides tapped Call at the same moment: each
                            // would otherwise ignore the other's offer and
                            // wait forever. Treat it as a mutual accept.
                            VoiceChatState.OFFERING -> {
                                offerTimeout?.cancel()
                                if (startCall()) nearbyManager.send(ControlMessage.VoiceChatAccept) else abortCall()
                            }
                            else -> Unit
                        }

                    ControlMessage.VoiceChatAccept ->
                        if (_callState.value == VoiceChatState.OFFERING) {
                            offerTimeout?.cancel()
                            if (!startCall()) abortCall()
                        }

                    ControlMessage.VoiceChatDecline ->
                        if (_callState.value == VoiceChatState.OFFERING) endCallLocally()

                    ControlMessage.VoiceChatEnd ->
                        when (_callState.value) {
                            VoiceChatState.ACTIVE, VoiceChatState.OFFERING, VoiceChatState.INCOMING_OFFER ->
                                endCallLocally()
                            else -> Unit
                        }

                    is ControlMessage.RouteLink -> Unit // not this session's concern
                }
            }
        }
        scope.launch {
            nearbyManager.incomingAudioChunks.collect { chunk: AudioChunk ->
                if (chunk.sequenceNumber < expectedSeq) return@collect // stale/duplicate -- drop
                expectedSeq = chunk.sequenceNumber + 1
                audioPlayback.submit(chunk.data)
            }
        }
        // A Nearby-level disconnect always ends any in-progress call -- there's
        // no connection left to carry it.
        scope.launch {
            nearbyManager.state.collect { state ->
                if (state !is NearbyState.Connected && _callState.value != VoiceChatState.IDLE) {
                    endCallLocally()
                }
            }
        }
    }

    fun offer() {
        if (_callState.value != VoiceChatState.IDLE) return
        if (!hasMicPermission()) {
            problem("Allow the microphone in RideLink first.")
            return
        }
        if (!holdMicType()) {
            problem("Couldn't start voice chat. Open RideLink and try again.")
            return
        }
        nearbyManager.send(ControlMessage.VoiceChatOffer)
        _callState.value = VoiceChatState.OFFERING
        // Nobody answered: stop ringing instead of waiting forever.
        offerTimeout?.cancel()
        offerTimeout = scope.launch {
            delay(OFFER_TIMEOUT_MS)
            cancelOffer()
        }
    }

    fun cancelOffer() {
        if (_callState.value != VoiceChatState.OFFERING) return
        nearbyManager.send(ControlMessage.VoiceChatEnd) // reused as a general "never mind"
        endCallLocally()
    }

    fun accept() {
        if (_callState.value != VoiceChatState.INCOMING_OFFER) return
        if (startCall()) {
            nearbyManager.send(ControlMessage.VoiceChatAccept)
        } else {
            nearbyManager.send(ControlMessage.VoiceChatDecline)
            endCallLocally()
        }
    }

    fun decline() {
        if (_callState.value != VoiceChatState.INCOMING_OFFER) return
        nearbyManager.send(ControlMessage.VoiceChatDecline)
        _callState.value = VoiceChatState.IDLE
    }

    fun end() {
        if (_callState.value != VoiceChatState.ACTIVE) return
        nearbyManager.send(ControlMessage.VoiceChatEnd)
        endCallLocally()
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        audioCapture.setMuted(muted)
    }

    /**
     * Forceful teardown regardless of current phase -- used when the
     * underlying Nearby session itself is ending, not just the call. The
     * session is not reusable afterwards (a new connection builds a new one).
     */
    fun shutdown() {
        endCallLocally()
        scope.cancel()
    }

    /** Starts audio for a call that was offered/accepted. False (with everything rolled back) if it can't start. */
    private fun startCall(): Boolean {
        if (!hasMicPermission()) {
            problem("Allow the microphone in RideLink to use voice chat.")
            return false
        }
        if (!holdMicType()) {
            problem("Couldn't start voice chat. Open RideLink and try again.")
            return false
        }
        return try {
            // Set before ACTIVE so the in-call notification has its start time.
            _callStartedAt.value = System.currentTimeMillis()
            requestAudioFocusAndMode()
            audioStarted = true
            routing.syncSelected()
            expectedSeq = 0
            audioPlayback.start()
            audioCapture.setMuted(_isMuted.value)
            audioCapture.start { data, seq -> nearbyManager.sendAudioChunk(seq, data, data.size) }
            _callState.value = VoiceChatState.ACTIVE
            true
        } catch (e: Exception) {
            // Microphone busy (e.g. a phone call), privacy switch on, device
            // refused the stream... Undo the half-started call.
            Log.e(TAG, "Could not start audio", e)
            teardownAudio()
            _callStartedAt.value = null
            releaseMicType()
            problem("Couldn't use the microphone. Is another app or a call using it?")
            false
        }
    }

    // Our side failed to start after the peer was already told yes.
    private fun abortCall() {
        nearbyManager.send(ControlMessage.VoiceChatEnd)
        endCallLocally()
    }

    private fun endCallLocally() {
        offerTimeout?.cancel()
        offerTimeout = null
        teardownAudio()
        _callStartedAt.value = null
        releaseMicType()
        _isMuted.value = false
        _callState.value = VoiceChatState.IDLE
    }

    // Safe to call when no audio was ever started.
    private fun teardownAudio() {
        if (!audioStarted) return
        audioStarted = false
        runCatching { audioCapture.stop() }
        runCatching { audioPlayback.stop() }
        // Undo the output choice (Bluetooth SCO / forced device) so it does
        // not outlive the call and keep the Bluetooth radio busy.
        runCatching { routing.release() }
        abandonAudioFocusAndMode()
    }

    private fun holdMicType(): Boolean {
        if (holdingMicType) return true
        val host = ConnectionNotificationHost.current ?: return true // no service to change
        if (!host.enterCallType()) return false
        holdingMicType = true
        return true
    }

    private fun releaseMicType() {
        if (!holdingMicType) return
        holdingMicType = false
        ConnectionNotificationHost.current?.exitCallType()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun problem(message: String) {
        runCatching { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    private fun requestAudioFocusAndMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocusAndMode() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private companion object {
        const val TAG = "VoiceChat"
        const val OFFER_TIMEOUT_MS = 45_000L
    }
}
