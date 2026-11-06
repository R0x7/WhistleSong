package com.whistlesong.dsp

import kotlin.math.ln
import kotlin.math.sqrt

/** One analysis frame of the pitch track. */
data class PitchFrame(
    val timeSec: Double,
    /** Detected fundamental in Hz, or null if the frame is unvoiced / silent. */
    val hz: Double?,
    /** Frame RMS level (0..1). */
    val rms: Double,
    /** YIN confidence, 0..1 (1 = perfectly periodic). */
    val confidence: Double,
) {
    val midi: Double? get() = hz?.let { hzToMidi(it) }
}

fun hzToMidi(hz: Double): Double = 69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)

fun midiToHz(midi: Double): Double = 440.0 * Math.pow(2.0, (midi - 69.0) / 12.0)

/**
 * YIN pitch detector (de Cheveigné & Kawahara, 2002), tuned for whistling.
 *
 * Whistles are close to pure sine waves somewhere between ~500 Hz and ~4 kHz, which
 * makes YIN very reliable. We only search lags covering [minHz, maxHz].
 */
class YinPitchDetector(
    private val sampleRate: Int,
    val frameSize: Int = 1024,
    private val minHz: Double = 350.0,
    private val maxHz: Double = 4200.0,
    private val threshold: Double = 0.15,
) {
    private val tauMin = (sampleRate / maxHz).toInt().coerceAtLeast(2)
    private val tauMax = (sampleRate / minHz).toInt().coerceAtMost(frameSize / 2)
    private val window = frameSize - tauMax
    private val diff = DoubleArray(tauMax + 1)
    private val cmnd = DoubleArray(tauMax + 1)

    /** Returns (hz or null, confidence, rms). */
    fun detect(samples: FloatArray, offset: Int = 0, timeSec: Double = 0.0): PitchFrame {
        var sumSq = 0.0
        for (i in 0 until frameSize) {
            val s = samples[offset + i].toDouble()
            sumSq += s * s
        }
        val rms = sqrt(sumSq / frameSize)
        if (rms < 1e-4) return PitchFrame(timeSec, null, rms, 0.0)

        // Step 1+2: difference function.
        for (tau in tauMin..tauMax) {
            var acc = 0.0
            var i = 0
            while (i < window) {
                val d = (samples[offset + i] - samples[offset + i + tau]).toDouble()
                acc += d * d
                i++
            }
            diff[tau] = acc
        }
        // Step 3: cumulative mean normalised difference. For lags below tauMin we
        // still need the running sum, so compute those too (cheaply, they're few).
        var running = 0.0
        for (tau in 1 until tauMin) {
            var acc = 0.0
            for (i in 0 until window) {
                val d = (samples[offset + i] - samples[offset + i + tau]).toDouble()
                acc += d * d
            }
            running += acc
        }
        for (tau in tauMin..tauMax) {
            running += diff[tau]
            cmnd[tau] = if (running == 0.0) 1.0 else diff[tau] * tau / running
        }

        // Step 4: absolute threshold — first dip below threshold, then walk to its minimum.
        var best = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) tau++
                best = tau
                break
            }
            tau++
        }
        if (best == -1) {
            // No clear periodicity.
            return PitchFrame(timeSec, null, rms, 0.0)
        }

        // Step 5: parabolic interpolation for sub-sample accuracy.
        val betterTau = if (best > tauMin && best < tauMax) {
            val s0 = cmnd[best - 1]
            val s1 = cmnd[best]
            val s2 = cmnd[best + 1]
            val denom = 2.0 * (2.0 * s1 - s2 - s0)
            if (denom != 0.0) best + (s2 - s0) / denom else best.toDouble()
        } else best.toDouble()

        val hz = sampleRate / betterTau
        val conf = (1.0 - cmnd[best]).coerceIn(0.0, 1.0)
        if (hz < minHz || hz > maxHz) return PitchFrame(timeSec, null, rms, conf)
        return PitchFrame(timeSec, hz, rms, conf)
    }

    /** Analyse a whole recording with the given hop size. */
    fun track(samples: FloatArray, hop: Int = sampleRate / 100): List<PitchFrame> {
        val out = ArrayList<PitchFrame>()
        var pos = 0
        while (pos + frameSize <= samples.size) {
            val t = (pos + frameSize / 2).toDouble() / sampleRate
            out += detect(samples, pos, t)
            pos += hop
        }
        return out
    }
}
