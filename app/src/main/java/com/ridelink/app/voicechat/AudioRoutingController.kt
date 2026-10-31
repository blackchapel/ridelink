package com.ridelink.app.voicechat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioOutputOption { SPEAKER, EARPIECE, BLUETOOTH }

/**
 * Routes call audio to speaker/earpiece/Bluetooth. Uses the modern
 * AudioManager.setCommunicationDevice() API on API 31+, and falls back to
 * the legacy setSpeakerphoneOn()/Bluetooth SCO APIs below that (minSdk 26).
 */
class AudioRoutingController(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _selected = MutableStateFlow(AudioOutputOption.EARPIECE)

    /**
     * The output currently in use, for the notification's Output button label.
     * Kept in step by [select] and re-read from the system by [syncSelected].
     */
    val selected: StateFlow<AudioOutputOption> = _selected.asStateFlow()

    /** Re-reads which output the system is actually using; call once a call starts. */
    @Suppress("DEPRECATION")
    fun syncSelected() {
        _selected.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (audioManager.communicationDevice?.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputOption.SPEAKER
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioOutputOption.BLUETOOTH
                else -> AudioOutputOption.EARPIECE
            }
        } else {
            when {
                audioManager.isBluetoothScoOn -> AudioOutputOption.BLUETOOTH
                audioManager.isSpeakerphoneOn -> AudioOutputOption.SPEAKER
                else -> AudioOutputOption.EARPIECE
            }
        }
    }

    /** Switches to the next available output (wrapping), for the notification's Output button. */
    fun cycleToNext() {
        val options = availableOptions()
        if (options.isEmpty()) return
        select(options[(options.indexOf(_selected.value) + 1) % options.size])
    }

    fun availableOptions(): List<AudioOutputOption> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val types = audioManager.availableCommunicationDevices.map { it.type }.toSet()
            val options = mutableListOf<AudioOutputOption>()
            if (AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in types) options += AudioOutputOption.EARPIECE
            if (AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in types) options += AudioOutputOption.SPEAKER
            if (AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types) options += AudioOutputOption.BLUETOOTH
            return options
        }
        // Pre-31: no enumeration API exists. Speaker/earpiece are always
        // assumed available; Bluetooth only if a call-capable (HFP) headset
        // is currently connected -- NOT the media-only A2DP profile, which
        // has no mic return path and isn't appropriate for call audio.
        val options = mutableListOf(AudioOutputOption.EARPIECE, AudioOutputOption.SPEAKER)
        if (hasConnectedBluetoothHeadset()) options += AudioOutputOption.BLUETOOTH
        return options
    }

    @Suppress("DEPRECATION")
    fun select(option: AudioOutputOption) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == option.toAudioDeviceType()
            }
            if (device != null && audioManager.setCommunicationDevice(device)) _selected.value = option
            return
        }
        when (option) {
            AudioOutputOption.SPEAKER -> {
                stopBluetoothScoLegacy()
                audioManager.isSpeakerphoneOn = true
            }
            AudioOutputOption.EARPIECE -> {
                stopBluetoothScoLegacy()
                audioManager.isSpeakerphoneOn = false
            }
            AudioOutputOption.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        }
        _selected.value = option
    }

    @Suppress("DEPRECATION")
    private fun stopBluetoothScoLegacy() {
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
    }

    private fun AudioOutputOption.toAudioDeviceType(): Int = when (this) {
        AudioOutputOption.SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        AudioOutputOption.EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        AudioOutputOption.BLUETOOTH -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun hasConnectedBluetoothHeadset(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
    }
}
