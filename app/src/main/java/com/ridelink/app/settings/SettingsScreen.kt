package com.ridelink.app.settings

import android.media.AudioTrack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ridelink.app.diagnostics.LinkStats
import com.ridelink.app.nearby.NearbyManager
import kotlinx.coroutines.delay

private const val CHUNK_MS = 20

/**
 * App settings. Developer mode reveals the radio mode, voice codec and live
 * link/audio stats. The stats are sampled once a second, and only while this
 * screen is on screen and the app is in the foreground -- nothing runs for
 * them otherwise.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { SettingsPreferences.get(context) }
    var developerMode by remember { mutableStateOf(prefs.developerMode) }
    var radioMode by remember { mutableStateOf(prefs.radioMode) }
    var codec by remember { mutableStateOf(prefs.codec) }
    var stats by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, developerMode) {
        if (!developerMode) {
            stats = emptyList()
            return@LaunchedEffect
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = LinkStats.snapshot()
            var tick = 0
            while (true) {
                delay(1000)
                tick++
                if (tick % 2 == 0) NearbyManager.current?.sendPing()
                val now = LinkStats.snapshot()
                stats = buildStats(previous, now)
                previous = now
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("Back") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Developer mode", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = developerMode,
                onCheckedChange = {
                    developerMode = it
                    prefs.developerMode = it
                },
            )
        }
        Text(
            text = "Shows the radio mode, voice codec and live link stats below. " +
                "The values you pick keep applying when this is switched off.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (developerMode) {
            SectionTitle("Radio mode")
            OptionRow(
                selected = radioMode == RadioMode.EFFICIENT,
                label = "Efficient (Bluetooth link, cooler, less battery)",
                onSelect = {
                    radioMode = RadioMode.EFFICIENT
                    prefs.radioMode = RadioMode.EFFICIENT
                },
            )
            OptionRow(
                selected = radioMode == RadioMode.PERFORMANCE,
                label = "Performance (may upgrade to Wi-Fi Direct, the old behaviour)",
                onSelect = {
                    radioMode = RadioMode.PERFORMANCE
                    prefs.radioMode = RadioMode.PERFORMANCE
                },
            )
            Text(
                text = "Set both phones to the same mode: phones on different modes will not find " +
                    "each other. Applies the next time a session starts (tap End, then pick your role again).",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionTitle("Voice codec")
            OptionRow(
                selected = codec == AudioCodec.OPUS,
                label = "Opus (about 16 kbps)",
                onSelect = {
                    codec = AudioCodec.OPUS
                    prefs.codec = AudioCodec.OPUS
                },
            )
            OptionRow(
                selected = codec == AudioCodec.PCM,
                label = "Raw PCM (128 kbps, the old behaviour)",
                onSelect = {
                    codec = AudioCodec.PCM
                    prefs.codec = AudioCodec.PCM
                },
            )
            Text(
                text = "What this phone sends. The other phone decodes either, so the two can differ. " +
                    "Applies from the next voice chat.",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionTitle("Link and audio stats")
            if (stats.isEmpty()) {
                Text(
                    text = "Collecting... (updates every second while this screen is open)",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                stats.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun buildStats(previous: LinkStats.Snapshot, now: LinkStats.Snapshot): List<Pair<String, String>> {
    val seconds = ((now.timeNanos - previous.timeNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
    fun perSecond(delta: Long): Double = delta / seconds

    val txPps = perSecond(now.txPackets - previous.txPackets)
    val txKbps = perSecond(now.txBytes - previous.txBytes) * 8 / 1000
    val rxPps = perSecond(now.rxPackets - previous.rxPackets)
    val rxKbps = perSecond(now.rxBytes - previous.rxBytes) * 8 / 1000

    val trackBufferMs = if (now.trackSampleRate > 0) now.trackBufferFrames * 1000 / now.trackSampleRate else 0
    val outputPath = if (now.trackSampleRate > 0) {
        "${now.trackSampleRate} Hz, buffer $trackBufferMs ms, " + performanceModeName(now.trackPerformanceMode)
    } else {
        "no call yet"
    }
    val estimatedLatency = if (now.trackSampleRate > 0) {
        "~${now.queueDepth * CHUNK_MS + trackBufferMs} ms (queue + track buffer)"
    } else {
        "-"
    }

    val encodeDelta = now.encodeFrames - previous.encodeFrames
    val decodeDelta = now.decodeFrames - previous.decodeFrames
    val codecCost = buildString {
        append(if (encodeDelta > 0) "enc ${(now.encodeNanos - previous.encodeNanos) / encodeDelta / 1000} us" else "enc -")
        append(" / ")
        append(if (decodeDelta > 0) "dec ${(now.decodeNanos - previous.decodeNanos) / decodeDelta / 1000} us" else "dec -")
    }

    return listOf(
        "Radio mode in use" to now.strategy,
        "Link round-trip" to if (now.rttMs >= 0) "${now.rttMs} ms" else "- (needs a connection)",
        "Reconnect attempts" to now.reconnectAttempts.toString(),
        "Sending" to "${now.txCodec}, ${"%.0f".format(txPps)} pkt/s, ${"%.1f".format(txKbps)} kbps",
        "Receiving" to "${now.rxCodec}, ${"%.0f".format(rxPps)} pkt/s, ${"%.1f".format(rxKbps)} kbps",
        "Lost / late / trimmed" to "${now.lostChunks} / ${now.lateChunks} / ${now.trimmedChunks}",
        "Playback queue" to "${now.queueDepth} x ${CHUNK_MS} ms",
        "Playback underruns" to now.underruns.toString(),
        "Output path" to outputPath,
        "Output latency estimate" to estimatedLatency,
        "Codec time per frame" to codecCost,
        "Mic" to (if (now.micActive) "on" else "off (idle or muted)") +
            ", echo cancel ${if (now.aecActive) "on" else "off"}, noise suppress ${if (now.nsActive) "on" else "off"}",
    )
}

private fun performanceModeName(mode: Int): String = when (mode) {
    AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> "low-latency path granted"
    AudioTrack.PERFORMANCE_MODE_POWER_SAVING -> "power-saving path"
    AudioTrack.PERFORMANCE_MODE_NONE -> "standard path (low-latency NOT granted)"
    else -> "unknown path"
}
