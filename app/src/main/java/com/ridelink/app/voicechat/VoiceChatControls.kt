package com.ridelink.app.voicechat

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Shared voice-chat UI for both roles. Connects a [VoiceChatSession] to the
 * stateless [CallCard]: it owns the microphone permission prompt, the call
 * clock and the audio-output refresh, and wires every button to the session.
 * [peerRole] is "Pillion" or "Rider" and [peerDevice] the other phone's name.
 */
@Composable
fun VoiceChatControls(voiceChat: VoiceChatSession, peerRole: String, peerDevice: String?) {
    val context = LocalContext.current
    val callState by voiceChat.callState.collectAsStateWithLifecycle()
    val isMuted by voiceChat.isMuted.collectAsStateWithLifecycle()
    val output by voiceChat.routing.selected.collectAsStateWithLifecycle()
    val startedAt by voiceChat.callStartedAt.collectAsStateWithLifecycle()

    // Microphone permission. Call and Accept need it; if it is missing the tap
    // asks first and then carries on by itself once it is granted.
    var hasRecordAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordAudioPermission = granted
        val action = pendingAction
        pendingAction = null
        if (granted) action?.invoke()
    }

    fun requireMicThen(action: () -> Unit) {
        if (hasRecordAudioPermission) {
            action()
        } else {
            pendingAction = action
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Call clock: ticks once a second, and only while the app is on screen --
    // nobody is looking otherwise, so there is no reason to wake up.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val callStart = startedAt
    LaunchedEffect(callStart, lifecycleOwner) {
        if (callStart == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val current = System.currentTimeMillis()
                now = current
                delay(1000L - (current % 1000L))
            }
        }
    }

    // Keep the output icon honest when a device appears or disappears mid-call
    // (e.g. a Bluetooth headset connecting): re-read what the system is using.
    val active = callState == VoiceChatState.ACTIVE
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        val audioManager = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                voiceChat.routing.syncSelected()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                voiceChat.routing.syncSelected()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    // Reduce motion: the system animator scale is 0 when animations are off.
    val animateRings = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }

    val elapsedText = formatCallDuration(if (callStart != null) now - callStart else 0L)

    Box(modifier = Modifier.padding(top = 16.dp)) {
        CallCard(
            model = CallCardModel(
                state = callState,
                peerRole = peerRole,
                peerDevice = peerDevice,
                elapsedText = elapsedText,
                isMuted = isMuted,
                output = output,
            ),
            onCall = { requireMicThen { voiceChat.offer() } },
            onCancel = { voiceChat.cancelOffer() },
            onAccept = { requireMicThen { voiceChat.accept() } },
            onDecline = { voiceChat.decline() },
            onToggleMute = { voiceChat.setMuted(!isMuted) },
            onCycleOutput = { voiceChat.routing.cycleToNext() },
            onEnd = { voiceChat.end() },
            animateRings = animateRings,
        )
    }
}
