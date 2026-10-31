package com.ridelink.app.voicechat

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Shared voice-chat UI for both roles -- offer/accept/decline, then in-call
 * mute and audio-output controls. [peerLabel] is just "your rider" or
 * "your pillion" so the same composable reads naturally on either screen.
 */
@Composable
fun VoiceChatControls(voiceChat: VoiceChatSession, peerLabel: String) {
    val context = LocalContext.current
    val callState by voiceChat.callState.collectAsStateWithLifecycle()
    val isMuted by voiceChat.isMuted.collectAsStateWithLifecycle()

    var hasRecordAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasRecordAudioPermission = granted }

    fun requireMicThen(action: () -> Unit) {
        if (hasRecordAudioPermission) action() else recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 16.dp),
    ) {
        when (callState) {
            VoiceChatState.IDLE -> {
                OutlinedButton(onClick = { requireMicThen { voiceChat.offer() } }) {
                    Text("Start voice chat")
                }
                if (!hasRecordAudioPermission) {
                    Text(
                        text = "Needs microphone access -- tap again after granting.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            VoiceChatState.OFFERING -> {
                Text(text = "Calling $peerLabel...", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { voiceChat.cancelOffer() }) {
                    Text("Cancel")
                }
            }

            VoiceChatState.INCOMING_OFFER -> {
                Text(
                    text = "$peerLabel wants to start voice chat",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Button(
                        onClick = { requireMicThen { voiceChat.accept() } },
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text("Accept")
                    }
                    OutlinedButton(onClick = { voiceChat.decline() }) {
                        Text("Decline")
                    }
                }
            }

            VoiceChatState.ACTIVE -> {
                Text(text = "Voice chat active", style = MaterialTheme.typography.bodyMedium)

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Mute", modifier = Modifier.padding(end = 8.dp))
                    Switch(checked = isMuted, onCheckedChange = { voiceChat.setMuted(it) })
                }

                // Re-read the available outputs whenever one appears/disappears (e.g. a
                // Bluetooth headset connecting mid-call), not just once per call.
                var outputsVersion by remember { mutableIntStateOf(0) }
                DisposableEffect(Unit) {
                    val audioManager = context.getSystemService(AudioManager::class.java)
                    val callback = object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                            outputsVersion++
                        }

                        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                            outputsVersion++
                        }
                    }
                    audioManager.registerAudioDeviceCallback(callback, null)
                    onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
                }
                val availableOutputs = remember(outputsVersion) { voiceChat.routing.availableOptions() }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableOutputs.forEach { option ->
                        OutlinedButton(onClick = { voiceChat.routing.select(option) }) {
                            Text(option.label())
                        }
                    }
                }

                TextButton(onClick = { voiceChat.end() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("End voice chat")
                }
            }
        }
    }
}

private fun AudioOutputOption.label(): String = when (this) {
    AudioOutputOption.SPEAKER -> "Speaker"
    AudioOutputOption.EARPIECE -> "Call speaker"
    AudioOutputOption.BLUETOOTH -> "Bluetooth"
}
