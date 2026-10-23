package com.ridelink.app.settings

import android.content.Context
import com.ridelink.app.diagnostics.LinkStats

/** Nearby Connections strategy used for the next session. Both phones must match. */
enum class RadioMode { EFFICIENT, PERFORMANCE }

/** Codec this phone sends voice in. The receiver decodes either, whatever its own setting. */
enum class AudioCodec { OPUS, PCM }

/**
 * Persisted app settings. Developer mode only controls whether the radio
 * mode / codec / stats controls are *visible* in Settings -- the saved values
 * apply whether or not Developer mode is on.
 */
class SettingsPreferences private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var developerMode: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DEVELOPER_MODE, value).apply()
            LinkStats.enabled = value
        }

    var radioMode: RadioMode
        get() = readEnum(KEY_RADIO_MODE, RadioMode.EFFICIENT, RadioMode.values())
        set(value) {
            prefs.edit().putString(KEY_RADIO_MODE, value.name).apply()
        }

    var codec: AudioCodec
        get() = readEnum(KEY_CODEC, AudioCodec.OPUS, AudioCodec.values())
        set(value) {
            prefs.edit().putString(KEY_CODEC, value.name).apply()
        }

    private fun <E : Enum<E>> readEnum(key: String, default: E, values: Array<E>): E {
        val name = prefs.getString(key, null) ?: return default
        return values.firstOrNull { it.name == name } ?: default
    }

    companion object {
        private const val PREFS_NAME = "ridelink_prefs"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_RADIO_MODE = "radio_mode"
        private const val KEY_CODEC = "audio_codec"

        @Volatile
        private var instance: SettingsPreferences? = null

        fun get(context: Context): SettingsPreferences =
            instance ?: synchronized(this) {
                instance ?: SettingsPreferences(context.applicationContext).also { instance = it }
            }
    }
}
