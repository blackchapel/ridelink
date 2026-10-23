package com.ridelink.app.diagnostics

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide link/audio counters behind Settings > Developer mode. Every
 * hot-path update goes through [record], which is a single volatile read when
 * Developer mode is off, so the counters cost nothing unless asked for.
 */
object LinkStats {

    @Volatile
    var enabled: Boolean = false

    val txPackets = AtomicLong()
    val txBytes = AtomicLong()
    val rxPackets = AtomicLong()
    val rxBytes = AtomicLong()
    val lostChunks = AtomicLong()
    val lateChunks = AtomicLong()
    val trimmedChunks = AtomicLong()
    val reconnectAttempts = AtomicLong()
    val encodeNanos = AtomicLong()
    val encodeFrames = AtomicLong()
    val decodeNanos = AtomicLong()
    val decodeFrames = AtomicLong()

    @Volatile var strategy: String = "-"
    @Volatile var txCodec: String = "-"
    @Volatile var rxCodec: String = "-"
    @Volatile var queueDepth: Int = 0
    @Volatile var underruns: Int = 0
    @Volatile var trackPerformanceMode: Int = -1
    @Volatile var trackBufferFrames: Int = 0
    @Volatile var trackSampleRate: Int = 0
    @Volatile var micActive: Boolean = false
    @Volatile var aecActive: Boolean = false
    @Volatile var nsActive: Boolean = false
    @Volatile var rttMs: Long = -1

    inline fun record(block: LinkStats.() -> Unit) {
        if (enabled) block()
    }

    fun snapshot(): Snapshot = Snapshot(
        timeNanos = System.nanoTime(),
        txPackets = txPackets.get(),
        txBytes = txBytes.get(),
        rxPackets = rxPackets.get(),
        rxBytes = rxBytes.get(),
        lostChunks = lostChunks.get(),
        lateChunks = lateChunks.get(),
        trimmedChunks = trimmedChunks.get(),
        reconnectAttempts = reconnectAttempts.get(),
        encodeNanos = encodeNanos.get(),
        encodeFrames = encodeFrames.get(),
        decodeNanos = decodeNanos.get(),
        decodeFrames = decodeFrames.get(),
        strategy = strategy,
        txCodec = txCodec,
        rxCodec = rxCodec,
        queueDepth = queueDepth,
        underruns = underruns,
        trackPerformanceMode = trackPerformanceMode,
        trackBufferFrames = trackBufferFrames,
        trackSampleRate = trackSampleRate,
        micActive = micActive,
        aecActive = aecActive,
        nsActive = nsActive,
        rttMs = rttMs,
    )

    data class Snapshot(
        val timeNanos: Long,
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long,
        val lostChunks: Long,
        val lateChunks: Long,
        val trimmedChunks: Long,
        val reconnectAttempts: Long,
        val encodeNanos: Long,
        val encodeFrames: Long,
        val decodeNanos: Long,
        val decodeFrames: Long,
        val strategy: String,
        val txCodec: String,
        val rxCodec: String,
        val queueDepth: Int,
        val underruns: Int,
        val trackPerformanceMode: Int,
        val trackBufferFrames: Int,
        val trackSampleRate: Int,
        val micActive: Boolean,
        val aecActive: Boolean,
        val nsActive: Boolean,
        val rttMs: Long,
    )
}
