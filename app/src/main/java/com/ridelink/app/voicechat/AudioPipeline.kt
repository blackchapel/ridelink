package com.ridelink.app.voicechat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import com.ridelink.app.diagnostics.LinkStats
import com.ridelink.app.nearby.AUDIO_HEADER_BYTES
import com.ridelink.app.nearby.TAG_AUDIO_OPUS
import com.ridelink.app.nearby.TAG_AUDIO_PCM
import com.ridelink.app.settings.AudioCodec
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Capture stays telephony-band (8kHz): the mic path is the one that also feeds
// AEC/NS, and 8kHz keeps both that work and the raw-PCM fallback's bitrate low.
internal const val CAPTURE_SAMPLE_RATE = 8000
internal const val CAPTURE_CHUNK_SAMPLES = 160 // 20ms at 8kHz
private const val CAPTURE_CHUNK_BYTES = CAPTURE_CHUNK_SAMPLES * 2

// Playback runs at 48kHz, the rate most devices' mixers run natively. A track
// at any other rate can't use the low-latency ("fast") path, which would make
// PERFORMANCE_MODE_LOW_LATENCY silently moot. Opus decodes straight to 48kHz;
// raw PCM is upsampled 6x.
internal const val PLAYBACK_SAMPLE_RATE = 48000
internal const val PLAYBACK_CHUNK_FRAMES = 960 // 20ms at 48kHz
private const val UPSAMPLE_FACTOR = PLAYBACK_SAMPLE_RATE / CAPTURE_SAMPLE_RATE

private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

// Hard cap on queued, not-yet-written packets; the playback thread also shaves
// a standing backlog well below this (see AudioPlayback.runPlayback).
private const val PLAYBACK_QUEUE_CAPACITY = 4
private const val SHAVE_WINDOW_CHUNKS = 50 // 1s
private const val SHAVE_MIN_DEPTH = 2
private const val MAX_CONCEALED_FRAMES = 2

private const val READ_OK = 1
private const val READ_RETRY = 0
private const val READ_FAILED = -1

/** Writes the 5-byte audio packet header: [tag][sequence number, big-endian]. */
internal fun writeAudioHeader(packet: ByteArray, tag: Byte, sequenceNumber: Int) {
    packet[0] = tag
    packet[1] = (sequenceNumber ushr 24).toByte()
    packet[2] = (sequenceNumber ushr 16).toByte()
    packet[3] = (sequenceNumber ushr 8).toByte()
    packet[4] = sequenceNumber.toByte()
}

internal fun readSequenceNumber(packet: ByteArray): Int =
    ((packet[1].toInt() and 0xFF) shl 24) or
        ((packet[2].toInt() and 0xFF) shl 16) or
        ((packet[3].toInt() and 0xFF) shl 8) or
        (packet[4].toInt() and 0xFF)

/**
 * Captures mic audio on a dedicated real-time thread and hands each finished
 * 20ms packet (already framed, and Opus-encoded if [codec] says so) to the
 * send callback as soon as it's ready -- no buffering between capture and
 * send. While muted the AudioRecord is actually stopped (not just its output
 * dropped) and the thread sleeps, so mute stops the mic, AEC and NS work too.
 */
internal class AudioCapture(private val codec: AudioCodec) {
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var captureThread: Thread? = null

    private val lock = ReentrantLock()
    private val unmuted = lock.newCondition()

    @Volatile
    private var muted = false

    /** Starts capturing; call [stop] to release everything. */
    fun start(sendPacket: (packet: ByteArray) -> Unit) {
        val minBuffer = AudioRecord.getMinBufferSize(CAPTURE_SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            CAPTURE_SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            // A few chunks of slack so a slow sendPayload can't overrun the mic buffer. Reads
            // return as soon as a chunk is ready, so this adds no latency.
            maxOf(minBuffer, CAPTURE_CHUNK_BYTES * 4),
        )
        audioRecord = record

        // Hardware/OEM-dependent -- isAvailable() gates each defensively.
        // Matters concretely here: the rider is expected to use the phone's
        // loudspeaker hands-free, so without echo cancellation the mic would
        // pick the pillion's own voice back up off the speaker.
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.also {
                if (!it.enabled) it.enabled = true
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.also {
                if (!it.enabled) it.enabled = true
            }
        }
        LinkStats.aecActive = echoCanceler?.enabled == true
        LinkStats.nsActive = noiseSuppressor?.enabled == true

        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runCapture(record, sendPacket)
        }, "RideLink-AudioCapture")
        captureThread = thread
        thread.start()
    }

    fun setMuted(muted: Boolean) {
        lock.withLock {
            this.muted = muted
            unmuted.signalAll()
        }
    }

    private fun runCapture(record: AudioRecord, sendPacket: (ByteArray) -> Unit) {
        val encoder = if (codec == AudioCodec.OPUS) OpusFrameEncoder() else null
        val pcmBytes = ByteArray(CAPTURE_CHUNK_BYTES)
        val pcmShorts = ShortArray(CAPTURE_CHUNK_SAMPLES)
        var sequenceNumber = 0
        var recording = false
        try {
            while (!Thread.currentThread().isInterrupted) {
                if (muted) {
                    if (recording) {
                        record.stop()
                        recording = false
                        LinkStats.micActive = false
                    }
                    lock.withLock { while (muted) unmuted.await() }
                    continue
                }
                if (!recording) {
                    record.startRecording()
                    recording = true
                    LinkStats.micActive = true
                }

                val packet: ByteArray
                if (encoder == null) {
                    packet = ByteArray(AUDIO_HEADER_BYTES + CAPTURE_CHUNK_BYTES)
                    val status = readChunk(record, packet, AUDIO_HEADER_BYTES)
                    if (status == READ_FAILED) return
                    if (status == READ_RETRY) continue
                    writeAudioHeader(packet, TAG_AUDIO_PCM, sequenceNumber++)
                } else {
                    val status = readChunk(record, pcmBytes, 0)
                    if (status == READ_FAILED) return
                    if (status == READ_RETRY) continue
                    for (i in 0 until CAPTURE_CHUNK_SAMPLES) {
                        pcmShorts[i] = ((pcmBytes[2 * i + 1].toInt() shl 8) or (pcmBytes[2 * i].toInt() and 0xFF)).toShort()
                    }
                    val startedAt = if (LinkStats.enabled) System.nanoTime() else 0L
                    packet = encoder.encodeFrame(pcmShorts, sequenceNumber) ?: continue
                    sequenceNumber++
                    if (startedAt != 0L) {
                        LinkStats.record {
                            encodeNanos.addAndGet(System.nanoTime() - startedAt)
                            encodeFrames.incrementAndGet()
                        }
                    }
                }
                sendPacket(packet)
            }
        } catch (e: Exception) {
            // Interrupted by stop(), or the record was released/failed under us --
            // either way capture is over.
        } finally {
            LinkStats.micActive = false
        }
    }

    private fun readChunk(record: AudioRecord, dest: ByteArray, offset: Int): Int {
        val read = record.read(dest, offset, CAPTURE_CHUNK_BYTES)
        return when {
            read == CAPTURE_CHUNK_BYTES -> READ_OK
            // Dead object / invalid operation: the record is gone, don't spin on it.
            read < 0 -> READ_FAILED
            else -> {
                if (read == 0) Thread.sleep(5)
                READ_RETRY
            }
        }
    }

    fun stop() {
        captureThread?.interrupt()
        captureThread = null
        audioRecord?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // Already stopped -- fine.
            }
            it.release()
        }
        audioRecord = null
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        LinkStats.micActive = false
    }
}

/**
 * Plays back received audio packets. [submitPacket] is called on the main
 * thread straight from Nearby's payload callback and only enqueues the raw
 * packet; sequencing, Opus decoding / upsampling and the AudioTrack write all
 * happen on a dedicated real-time thread.
 */
internal class AudioPlayback {
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    @Volatile
    private var queue: ArrayBlockingQueue<ByteArray>? = null

    // Previous 8kHz sample, so the 6x linear-interpolation upsampler is continuous across packets.
    private var upsampleLast = 0

    fun start() {
        val minBuffer = AudioTrack.getMinBufferSize(PLAYBACK_SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val twoChunksBytes = PLAYBACK_CHUNK_FRAMES * 2 * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PLAYBACK_SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .setEncoding(ENCODING)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, twoChunksBytes))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        // We never write far ahead of real time (the queue below is the jitter
        // buffer), so a bigger track buffer would only ever add latency.
        track.setBufferSizeInFrames(PLAYBACK_CHUNK_FRAMES * 2)
        audioTrack = track
        LinkStats.trackSampleRate = PLAYBACK_SAMPLE_RATE
        LinkStats.trackBufferFrames = track.bufferSizeInFrames
        LinkStats.trackPerformanceMode = track.performanceMode
        track.play()

        val chunkQueue = ArrayBlockingQueue<ByteArray>(PLAYBACK_QUEUE_CAPACITY)
        queue = chunkQueue
        upsampleLast = 0

        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runPlayback(track, chunkQueue)
        }, "RideLink-AudioPlayback")
        playbackThread = thread
        thread.start()
    }

    /** Enqueues a raw packet for playback, dropping the oldest queued one if full. Cheap; any thread. */
    fun submitPacket(packet: ByteArray) {
        val chunkQueue = queue ?: return
        if (!chunkQueue.offer(packet)) {
            chunkQueue.poll()
            chunkQueue.offer(packet)
            LinkStats.record { trimmedChunks.incrementAndGet() }
        }
    }

    private fun runPlayback(track: AudioTrack, chunkQueue: ArrayBlockingQueue<ByteArray>) {
        val pcm = ShortArray(PLAYBACK_CHUNK_FRAMES)
        var opusDecoder: OpusFrameDecoder? = null
        var expectedSeq = -1 // -1: nothing received yet this call
        var windowChunks = 0
        var windowMinDepth = Int.MAX_VALUE
        try {
            while (!Thread.currentThread().isInterrupted) {
                val packet = chunkQueue.take()

                // Production and playback both run at exactly 50 chunks/s, so a
                // backlog from one burst would otherwise never drain. If the
                // queue never dipped below SHAVE_MIN_DEPTH over a whole window,
                // drop one stale chunk (20ms) to claw the latency back.
                val depth = chunkQueue.size
                if (depth < windowMinDepth) windowMinDepth = depth
                if (++windowChunks >= SHAVE_WINDOW_CHUNKS) {
                    if (windowMinDepth >= SHAVE_MIN_DEPTH && chunkQueue.poll() != null) {
                        LinkStats.record { trimmedChunks.incrementAndGet() }
                    }
                    LinkStats.record {
                        queueDepth = depth
                        underruns = track.underrunCount
                    }
                    windowChunks = 0
                    windowMinDepth = Int.MAX_VALUE
                }

                if (packet.size <= AUDIO_HEADER_BYTES) continue
                val sequenceNumber = readSequenceNumber(packet)
                if (sequenceNumber < expectedSeq) {
                    LinkStats.record { lateChunks.incrementAndGet() }
                    continue // stale/duplicate
                }
                val missing = if (expectedSeq < 0) 0 else sequenceNumber - expectedSeq
                expectedSeq = sequenceNumber + 1
                if (missing > 0) LinkStats.record { lostChunks.addAndGet(missing.toLong()) }

                val frames: Int
                if (packet[0] == TAG_AUDIO_OPUS) {
                    val decoder = opusDecoder ?: OpusFrameDecoder().also { opusDecoder = it }
                    // Cover a short gap with synthesized audio instead of a click;
                    // a longer gap is just skipped over.
                    for (i in 0 until minOf(missing, MAX_CONCEALED_FRAMES)) {
                        val concealed = decoder.conceal(pcm)
                        if (concealed > 0 && track.write(pcm, 0, concealed) < 0) return
                    }
                    val startedAt = if (LinkStats.enabled) System.nanoTime() else 0L
                    frames = decoder.decode(packet, pcm)
                    if (startedAt != 0L) {
                        LinkStats.record {
                            decodeNanos.addAndGet(System.nanoTime() - startedAt)
                            decodeFrames.incrementAndGet()
                            rxCodec = "Opus"
                        }
                    }
                } else {
                    frames = upsamplePcm(packet, pcm)
                    LinkStats.record { rxCodec = "Raw PCM" }
                }
                if (frames > 0 && track.write(pcm, 0, frames) < 0) return
            }
        } catch (e: InterruptedException) {
            // Interrupted by stop() -- expected, not an error.
        }
    }

    // Raw 8kHz PCM16 (little-endian) after the header -> 48kHz, linear interpolation.
    private fun upsamplePcm(packet: ByteArray, out: ShortArray): Int {
        val samples = minOf((packet.size - AUDIO_HEADER_BYTES) / 2, CAPTURE_CHUNK_SAMPLES)
        var last = upsampleLast
        var o = 0
        for (i in 0 until samples) {
            val at = AUDIO_HEADER_BYTES + 2 * i
            val current = (packet[at + 1].toInt() shl 8) or (packet[at].toInt() and 0xFF)
            for (k in 1..UPSAMPLE_FACTOR) {
                out[o++] = (last + (current - last) * k / UPSAMPLE_FACTOR).toShort()
            }
            last = current
        }
        upsampleLast = last
        return o
    }

    fun stop() {
        playbackThread?.interrupt()
        playbackThread = null
        queue = null
        audioTrack?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // Already stopped -- fine.
            }
            it.release()
        }
        audioTrack = null
    }
}
