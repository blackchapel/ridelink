package com.ridelink.app.voicechat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.ridelink.app.nearby.AudioChunk
import com.ridelink.app.nearby.ControlMessage
import com.ridelink.app.nearby.NearbyManager
import com.ridelink.app.nearby.NearbyState
import com.ridelink.app.notifications.ConnectionNotificationHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioCapture = AudioCapture()
    private val audioPlayback = AudioPlayback()
    val routing = AudioRoutingController(context)

    private val _callState = MutableStateFlow(VoiceChatState.IDLE)
    val callState: StateFlow<VoiceChatState> = _callState.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private var focusRequest: AudioFocusRequest? = null

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
                        if (_callState.value == VoiceChatState.IDLE) {
                            _callState.value = VoiceChatState.INCOMING_OFFER
                        }

                    ControlMessage.VoiceChatAccept ->
                        if (_callState.value == VoiceChatState.OFFERING) beginCall()

                    ControlMessage.VoiceChatDecline ->
                        if (_callState.value == VoiceChatState.OFFERING) {
                            _callState.value = VoiceChatState.IDLE
                        }

                    ControlMessage.VoiceChatEnd ->
                        when (_callState.value) {
                            VoiceChatState.ACTIVE -> endCallLocally()
                            VoiceChatState.INCOMING_OFFER -> _callState.value = VoiceChatState.IDLE
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
        nearbyManager.send(ControlMessage.VoiceChatOffer)
        _callState.value = VoiceChatState.OFFERING
    }

    fun cancelOffer() {
        if (_callState.value != VoiceChatState.OFFERING) return
        nearbyManager.send(ControlMessage.VoiceChatEnd) // reused as a general "never mind"
        _callState.value = VoiceChatState.IDLE
    }

    fun accept() {
        if (_callState.value != VoiceChatState.INCOMING_OFFER) return
        nearbyManager.send(ControlMessage.VoiceChatAccept)
        beginCall()
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
     * underlying Nearby session itself is ending, not just the call.
     */
    fun shutdown() {
        if (_callState.value == VoiceChatState.ACTIVE) {
            endCallLocally()
        } else {
            _callState.value = VoiceChatState.IDLE
        }
    }

    private fun beginCall() {
        ConnectionNotificationHost.current?.enterCallType()
        requestAudioFocusAndMode()
        expectedSeq = 0
        audioPlayback.start()
        audioCapture.setMuted(_isMuted.value)
        audioCapture.start { data, seq -> nearbyManager.sendAudioChunk(seq, data, data.size) }
        _callState.value = VoiceChatState.ACTIVE
    }

    private fun endCallLocally() {
        audioCapture.stop()
        audioPlayback.stop()
        abandonAudioFocusAndMode()
        ConnectionNotificationHost.current?.exitCallType()
        _isMuted.value = false
        _callState.value = VoiceChatState.IDLE
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
}
