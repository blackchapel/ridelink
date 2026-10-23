package com.ridelink.app.voicechat

import com.ridelink.app.nearby.AUDIO_HEADER_BYTES
import com.ridelink.app.nearby.TAG_AUDIO_OPUS
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusBandwidth
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal

// ~40 bytes per 20ms frame (vs 320 raw), so the radio sends a tenth of the data.
private const val OPUS_BITRATE_BPS = 16_000
private const val OPUS_COMPLEXITY = 2
private const val MAX_OPUS_PACKET_BYTES = 200

/**
 * Pure-Java Opus (Concentus), chosen over MediaCodec because it is
 * synchronous per 20ms frame: no codec-specific-data setup and no hidden
 * pipeline buffering adding latency. Narrowband, voice-tuned, constant
 * bitrate so packet size (and so airtime) is predictable.
 */
internal class OpusFrameEncoder {
    private val encoder = OpusEncoder(CAPTURE_SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(OPUS_BITRATE_BPS)
        setComplexity(OPUS_COMPLEXITY)
        setUseVBR(false)
        setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        setMaxBandwidth(OpusBandwidth.OPUS_BANDWIDTH_NARROWBAND)
    }
    private val scratch = ByteArray(MAX_OPUS_PACKET_BYTES)

    /** Encodes one 20ms frame into a ready-to-send framed packet, or null if the encoder rejected it. */
    fun encodeFrame(pcm: ShortArray, sequenceNumber: Int): ByteArray? {
        val length = try {
            encoder.encode(pcm, 0, CAPTURE_CHUNK_SAMPLES, scratch, 0, scratch.size)
        } catch (e: Exception) {
            return null
        }
        if (length <= 0) return null
        val packet = ByteArray(AUDIO_HEADER_BYTES + length)
        writeAudioHeader(packet, TAG_AUDIO_OPUS, sequenceNumber)
        System.arraycopy(scratch, 0, packet, AUDIO_HEADER_BYTES, length)
        return packet
    }
}

/** Decodes straight to 48kHz so the AudioTrack can run at the device's native rate. */
internal class OpusFrameDecoder {
    private val decoder = OpusDecoder(PLAYBACK_SAMPLE_RATE, 1)

    /** Decodes a framed packet into [out]; returns samples written (0 if the packet was unusable). */
    fun decode(packet: ByteArray, out: ShortArray): Int = try {
        decoder.decode(
            packet, AUDIO_HEADER_BYTES, packet.size - AUDIO_HEADER_BYTES,
            out, 0, PLAYBACK_CHUNK_FRAMES, false,
        )
    } catch (e: Exception) {
        0
    }

    /** Synthesizes one frame to cover a lost packet (packet-loss concealment). */
    fun conceal(out: ShortArray): Int = try {
        decoder.decode(null, 0, 0, out, 0, PLAYBACK_CHUNK_FRAMES, false)
    } catch (e: Exception) {
        0
    }
}
