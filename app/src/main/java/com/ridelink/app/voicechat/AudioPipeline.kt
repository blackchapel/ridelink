package com.ridelink.app.voicechat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue

// Telephony-band voice quality (not 16kHz): halves the raw bitrate to
// 128kbps at exactly the point where Nearby Connections' real achievable
// throughput over Bluetooth at bike-riding range is a genuine unknown --
// the conservative choice until real-device testing shows there's headroom.
internal const val VOICE_SAMPLE_RATE = 8000
private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
private const val CHUNK_BYTES = 320 // 20ms at 8kHz/16-bit mono
private const val PLAYBACK_QUEUE_CAPACITY = 4 // ~80ms bounded jitter buffer

/**
 * Captures mic audio on a dedicated real-time thread, handing each 20ms
 * chunk straight to [onChunk] as soon as it's read -- no buffering between
 * capture and send, so the only latency this side contributes is the time
 * to fill one chunk.
 */
internal class AudioCapture {
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var captureThread: Thread? = null

    @Volatile
    private var muted = false

    /** Starts capturing; call [stop] to release everything. */
    fun start(onChunk: (data: ByteArray, sequenceNumber: Int) -> Unit) {
        val minBuffer = AudioRecord.getMinBufferSize(VOICE_SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            VOICE_SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            maxOf(minBuffer, CHUNK_BYTES * 2),
        )
        audioRecord = record

        // Hardware/OEM-dependent -- isAvailable() gates each defensively.
        // Matters concretely here: the rider is expected to use the phone's
        // loudspeaker hands-free, so without echo cancellation the mic would
        // pick the pillion's own voice back up off the speaker.
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        }

        record.startRecording()
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var sequenceNumber = 0
            val buffer = ByteArray(CHUNK_BYTES)
            while (!Thread.currentThread().isInterrupted) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val seq = sequenceNumber++
                // Muted frames are simply dropped rather than sent as
                // zeroed bytes -- saves bandwidth too. Sequence numbers keep
                // advancing regardless, so the receiver sees an ordinary gap
                // to skip over on unmute, nothing to special-case.
                if (!muted) {
                    onChunk(buffer.copyOf(read), seq)
                }
            }
        }, "RideLink-AudioCapture")
        captureThread = thread
        thread.start()
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
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
    }
}

/**
 * Plays back audio chunks as they arrive via [submit], through a small
 * bounded queue that drops the oldest chunk instead of growing without
 * bound -- a receive-side stall loses at most ~80ms of stale audio rather
 * than ever falling further behind live.
 */
internal class AudioPlayback {
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var queue: ArrayBlockingQueue<ByteArray>? = null

    fun start() {
        val minBuffer = AudioTrack.getMinBufferSize(VOICE_SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(VOICE_SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .setEncoding(ENCODING)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        audioTrack = track
        track.play()

        val chunkQueue = ArrayBlockingQueue<ByteArray>(PLAYBACK_QUEUE_CAPACITY)
        queue = chunkQueue

        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val chunk = chunkQueue.take()
                    track.write(chunk, 0, chunk.size)
                }
            } catch (e: InterruptedException) {
                // Interrupted by stop() -- expected, not an error.
            }
        }, "RideLink-AudioPlayback")
        playbackThread = thread
        thread.start()
    }

    /** Enqueues a chunk for playback, dropping the oldest queued chunk if full. */
    fun submit(chunk: ByteArray) {
        val chunkQueue = queue ?: return
        if (!chunkQueue.offer(chunk)) {
            chunkQueue.poll()
            chunkQueue.offer(chunk)
        }
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
