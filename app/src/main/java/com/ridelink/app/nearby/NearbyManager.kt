package com.ridelink.app.nearby

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.ridelink.app.diagnostics.LinkStats
import com.ridelink.app.settings.RadioMode
import com.ridelink.app.settings.SettingsPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Rider and pillion are always exactly one pair: one advertiser (pillion),
 * one discoverer (rider). The Nearby [Strategy] is chosen per session from
 * [RadioMode] (Settings), and both phones must use the same one.
 */
sealed class NearbyState {
    data object Idle : NearbyState()
    data object Searching : NearbyState()
    data class Connected(val endpointId: String, val endpointName: String) : NearbyState()
    data object Disconnected : NearbyState()
}

/** Strips the shared advertising prefix so the UI can show a plain device name. */
fun formatPeerName(endpointName: String): String = endpointName.removePrefix("RideLink-")

/**
 * Small control-channel messages, sent as tagged BYTES payloads -- distinct
 * from the audio packets sent via [NearbyManager.sendAudioPacket], which use
 * their own tags ([TAG_AUDIO_PCM], [TAG_AUDIO_OPUS]) and never flow through
 * this envelope (raw audio bytes decoded as UTF-8 text would be silently
 * corrupted).
 */
sealed class ControlMessage {
    data class RouteLink(val url: String) : ControlMessage()
    data object VoiceChatOffer : ControlMessage()
    data object VoiceChatAccept : ControlMessage()
    data object VoiceChatDecline : ControlMessage()
    data object VoiceChatEnd : ControlMessage()

    fun encode(): ByteArray = when (this) {
        is RouteLink -> byteArrayOf(TAG_ROUTE_LINK) + url.toByteArray(Charsets.UTF_8)
        VoiceChatOffer -> byteArrayOf(TAG_VOICE_OFFER)
        VoiceChatAccept -> byteArrayOf(TAG_VOICE_ACCEPT)
        VoiceChatDecline -> byteArrayOf(TAG_VOICE_DECLINE)
        VoiceChatEnd -> byteArrayOf(TAG_VOICE_END)
    }

    companion object {
        private const val TAG_ROUTE_LINK: Byte = 1
        private const val TAG_VOICE_OFFER: Byte = 2
        private const val TAG_VOICE_ACCEPT: Byte = 3
        private const val TAG_VOICE_DECLINE: Byte = 4
        private const val TAG_VOICE_END: Byte = 5

        fun decode(bytes: ByteArray): ControlMessage? {
            if (bytes.isEmpty()) return null
            return when (bytes[0]) {
                TAG_ROUTE_LINK -> RouteLink(String(bytes, 1, bytes.size - 1, Charsets.UTF_8))
                TAG_VOICE_OFFER -> VoiceChatOffer
                TAG_VOICE_ACCEPT -> VoiceChatAccept
                TAG_VOICE_DECLINE -> VoiceChatDecline
                TAG_VOICE_END -> VoiceChatEnd
                else -> null
            }
        }
    }
}

// Audio packet wire format: [tag:1][sequence:4, big-endian][payload...].
internal const val TAG_AUDIO_PCM: Byte = 6
internal const val TAG_AUDIO_OPUS: Byte = 7
internal const val AUDIO_HEADER_BYTES = 5

// Link-latency probe, only sent while Settings' developer stats are open.
private const val TAG_PING: Byte = 8
private const val TAG_PONG: Byte = 9

/**
 * Receives each raw audio packet (header included) straight from Nearby's
 * payload callback, on the main thread. Implementations must only hand the
 * packet off -- no decoding or blocking work.
 */
fun interface AudioPacketSink {
    fun onAudioPacket(packet: ByteArray)
}

private enum class RadioRole { ADVERTISER, DISCOVERER }

/**
 * Thin wrapper around Google Play services' Nearby Connections API.
 * Handles the whole handoff over local Bluetooth/Wi-Fi -- no server involved.
 *
 * Radio lifecycle: advertising/discovery run only until a connection is
 * established, then stop (scanning/beaconing for a whole ride is pure drain).
 * An unexpected drop restarts the same role with capped exponential backoff.
 * All callbacks, the reconnect timer and the public start/stop calls run on
 * the main thread.
 */
class NearbyManager(context: Context) {

    private val appContext = context.applicationContext
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    @Volatile
    private var connectedEndpointId: String? = null
    private var pendingEndpointName: String? = null

    private var role: RadioRole? = null
    private var advertising = false
    private var discovering = false
    private var connecting = false
    private var stopped = false
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<NearbyState>(NearbyState.Idle)
    val state: StateFlow<NearbyState> = _state.asStateFlow()

    private val _receivedMessages = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 8)
    val receivedMessages: SharedFlow<ControlMessage> = _receivedMessages

    /** Set by the voice-chat session for the duration of a call; audio is dropped when null. */
    @Volatile
    var audioSink: AudioPacketSink? = null

    init {
        current = this
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            if (bytes.isEmpty()) return
            when (bytes[0]) {
                TAG_AUDIO_PCM, TAG_AUDIO_OPUS -> {
                    LinkStats.record {
                        rxPackets.incrementAndGet()
                        rxBytes.addAndGet(bytes.size.toLong())
                    }
                    audioSink?.onAudioPacket(bytes)
                }
                TAG_PING -> {
                    val reply = bytes.copyOf()
                    reply[0] = TAG_PONG
                    connectionsClient.sendPayload(endpointId, Payload.fromBytes(reply))
                }
                TAG_PONG -> {
                    if (bytes.size >= PING_BYTES) {
                        val sentAt = ByteBuffer.wrap(bytes).getLong(1)
                        LinkStats.rttMs = (System.nanoTime() - sentAt) / 1_000_000L
                    }
                }
                else -> ControlMessage.decode(bytes)?.let { _receivedMessages.tryEmit(it) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Every payload here (control messages, audio packets) is a single
            // small BYTES payload -- no progress UI needed.
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Both sides are trusted for this two-person use case: auto-accept.
            pendingEndpointName = info.endpointName
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener {
                    connecting = false
                    scheduleReconnect()
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            connecting = false
            if (result.status.isSuccess) {
                connectedEndpointId = endpointId
                reconnectAttempt = 0
                cancelReconnect()
                // Connected: nothing left to find or be found by. Leaving these
                // running would keep the radios scanning/beaconing all ride.
                connectionsClient.stopDiscovery()
                connectionsClient.stopAdvertising()
                discovering = false
                advertising = false
                _state.value = NearbyState.Connected(endpointId, pendingEndpointName ?: "the other device")
            } else {
                scheduleReconnect()
            }
        }

        override fun onDisconnected(endpointId: String) {
            connecting = false
            LinkStats.rttMs = -1
            if (connectedEndpointId == endpointId) {
                connectedEndpointId = null
            }
            if (!stopped) scheduleReconnect()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (stopped || connecting || connectedEndpointId != null) return
            connecting = true
            connectionsClient.requestConnection(LOCAL_ENDPOINT_NAME, endpointId, connectionLifecycleCallback)
                .addOnFailureListener {
                    connecting = false
                    scheduleReconnect()
                }
        }

        override fun onEndpointLost(endpointId: String) {
            // Discovery keeps running until connected; onDisconnected handles an active connection dropping.
        }
    }

    /** Pillion side. Idempotent: re-entering the screen while already advertising/connected is a no-op. */
    fun startAdvertising() {
        if (role == RadioRole.ADVERTISER && (advertising || connectedEndpointId != null)) return
        stopped = false
        role = RadioRole.ADVERTISER
        reconnectAttempt = 0
        cancelReconnect()
        beginAdvertising()
    }

    /** Rider side. Idempotent, same as [startAdvertising]. */
    fun startDiscovery() {
        if (role == RadioRole.DISCOVERER && (discovering || connectedEndpointId != null)) return
        stopped = false
        role = RadioRole.DISCOVERER
        reconnectAttempt = 0
        cancelReconnect()
        beginDiscovery()
    }

    private fun beginAdvertising() {
        _state.value = NearbyState.Searching
        val options = AdvertisingOptions.Builder()
            .setStrategy(currentStrategy())
            // Only matters while pairing (advertising stops on connect): favour finding each other fast.
            .setLowPower(false)
            .build()
        advertising = true
        connectionsClient
            .startAdvertising(LOCAL_ENDPOINT_NAME, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnFailureListener {
                advertising = false
                scheduleReconnect()
            }
    }

    private fun beginDiscovery() {
        _state.value = NearbyState.Searching
        val options = DiscoveryOptions.Builder()
            .setStrategy(currentStrategy())
            .setLowPower(false)
            .build()
        discovering = true
        connectionsClient
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnFailureListener {
                discovering = false
                scheduleReconnect()
            }
    }

    private fun currentStrategy(): Strategy {
        val efficient = SettingsPreferences.get(appContext).radioMode == RadioMode.EFFICIENT
        LinkStats.strategy = if (efficient) "P2P_CLUSTER (Efficient)" else "P2P_POINT_TO_POINT (Performance)"
        return if (efficient) Strategy.P2P_CLUSTER else Strategy.P2P_POINT_TO_POINT
    }

    private fun scheduleReconnect() {
        if (stopped || role == null) return
        cancelReconnect()
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _state.value = NearbyState.Disconnected
            return
        }
        val delayMs = minOf(RECONNECT_BASE_DELAY_MS shl reconnectAttempt, RECONNECT_MAX_DELAY_MS)
        reconnectAttempt++
        LinkStats.record { reconnectAttempts.incrementAndGet() }
        _state.value = NearbyState.Searching
        val runnable = Runnable {
            reconnectRunnable = null
            if (!stopped && _state.value !is NearbyState.Connected) restartRadio()
        }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun restartRadio() {
        connecting = false
        when (role) {
            RadioRole.ADVERTISER -> {
                connectionsClient.stopAdvertising()
                beginAdvertising()
            }
            RadioRole.DISCOVERER -> {
                connectionsClient.stopDiscovery()
                beginDiscovery()
            }
            null -> Unit
        }
    }

    fun send(message: ControlMessage) {
        val endpointId = connectedEndpointId ?: return
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(message.encode()))
    }

    /**
     * Sends one fully framed audio packet (see the wire-format note above) as
     * its own small BYTES payload -- fire-and-forget. Chosen over a single
     * long-lived Payload.fromStream() specifically so a lost/delayed chunk
     * can never stall everything queued behind it: STREAM payloads are
     * delivered reliably and in order, which is exactly wrong for real-time
     * audio on a link that can momentarily degrade (bike-riding range).
     * Called from the audio capture thread.
     */
    fun sendAudioPacket(packet: ByteArray) {
        val endpointId = connectedEndpointId ?: return
        LinkStats.record {
            txPackets.incrementAndGet()
            txBytes.addAndGet(packet.size.toLong())
        }
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(packet))
    }

    /** Link-latency probe for the developer stats screen; the peer answers automatically. */
    fun sendPing() {
        val endpointId = connectedEndpointId ?: return
        val bytes = ByteArray(PING_BYTES)
        bytes[0] = TAG_PING
        ByteBuffer.wrap(bytes).putLong(1, System.nanoTime())
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    fun stop() {
        stopped = true
        cancelReconnect()
        role = null
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        advertising = false
        discovering = false
        connecting = false
        connectedEndpointId = null
        audioSink = null
        if (current === this) current = null
        _state.value = NearbyState.Idle
    }

    companion object {
        const val SERVICE_ID = "com.ridelink.SERVICE"
        private val LOCAL_ENDPOINT_NAME = "RideLink-${Build.MODEL}"

        private const val PING_BYTES = 9
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L

        /** The active session's manager, so Settings can probe link latency. At most one exists at a time. */
        @Volatile
        var current: NearbyManager? = null
    }
}
