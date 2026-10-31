package com.ridelink.app.voicechat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

enum class AudioOutputOption { SPEAKER, EARPIECE, BLUETOOTH }

/**
 * Routes call audio to speaker/earpiece/Bluetooth. Uses the modern
 * AudioManager.setCommunicationDevice() API on API 31+, and falls back to
 * the legacy setSpeakerphoneOn()/Bluetooth SCO APIs below that (minSdk 26).
 */
class AudioRoutingController(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

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
            if (device != null) audioManager.setCommunicationDevice(device)
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
