package com.whistlesong.synth

import com.whistlesong.dsp.midiToHz
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

enum class Instrument {
    PULSE_25, PULSE_12, TRIANGLE_8BIT,
    SAW_LEAD, SAW_PAD, SYNTH_BASS,
    LEAD_GUITAR, POWER_GUITAR, PICK_BASS,
    EPIANO, SUB_BASS,
    VIBES, PIANO, UPRIGHT_BASS,
    MUSIC_BOX,
}

enum class Drum { KICK, SNARE, CLAP, HAT, OPEN_HAT, CRASH, RIDE, CHIP_KICK, CHIP_SNARE, CHIP_HAT }

data class NoteEvent(val inst: Instrument, val midi: Int, val startSec: Double, val durSec: Double, val vel: Float)
data class DrumEvent(val drum: Drum, val startSec: Double, val vel: Float)

data class MasterFx(
    val delaySec: Double = 0.0,
    val delayFeedback: Double = 0.0,
    val delayMix: Double = 0.0,
    val lowpassHz: Double = 0.0,
    val crackle: Boolean = false,
    val bitCrush: Boolean = false,
)

data class Arrangement(
    val notes: List<NoteEvent>,
    val drums: List<DrumEvent>,
    val lengthSec: Double,
    val fx: MasterFx,
)

/**
 * A small software synthesiser. Everything is rendered offline into a float buffer, which is
 * then streamed to AudioTrack (or written to a WAV file).
 */
class Synth(private val sr: Int = 44100) {
    private val rng = Random(1234)

    fun render(arr: Arrangement): FloatArray {
        val total = ((arr.lengthSec + 2.5) * sr).toInt()
        val buf = FloatArray(total)
        for (n in arr.notes) renderNote(buf, n)
        for (d in arr.drums) renderDrum(buf, d)
        applyFx(buf, arr.fx)
        // Trim trailing silence (keep a short tail).
        var end = buf.size
        while (end > 0 && abs(buf[end - 1]) < 0.002f) end--
        return buf.copyOf(min(buf.size, end + sr / 10))
    }

    // ------------------------------------------------------------------ helpers

    private fun lpCoef(hz: Double) = 1.0 - exp(-2.0 * PI * hz / sr)

    private fun polyBlep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    private fun saw(phase: Double, dt: Double) = 2.0 * phase - 1.0 - polyBlep(phase, dt)

    /** Linear-attack, exponential-ish decay to sustain, linear release envelope. */
    private fun adsr(t: Double, dur: Double, a: Double, d: Double, s: Double, r: Double): Double {
        val held = when {
            t < a -> t / a
            t < a + d -> 1.0 - (1.0 - s) * ((t - a) / d)
            else -> s
        }
        if (t < dur) return held
        val atRelease = when {
            dur < a -> dur / a
            dur < a + d -> 1.0 - (1.0 - s) * ((dur - a) / d)
            else -> s
        }
        val rt = t - dur
        return if (rt >= r) 0.0 else atRelease * (1.0 - rt / r)
    }

    // ------------------------------------------------------------------ pitched

    private fun renderNote(buf: FloatArray, ev: NoteEvent) {
        val f = midiToHz(ev.midi.toDouble())
        val start = (ev.startSec * sr).toInt()
        val vel = ev.vel.toDouble()
        val dur = ev.durSec
        when (ev.inst) {
            Instrument.PULSE_25, Instrument.PULSE_12 -> {
                val duty = if (ev.inst == Instrument.PULSE_25) 0.25 else 0.125
                val rel = 0.03
                var ph = 0.0
                loop(buf, start, dur + rel) { t ->
                    val vib = if (t > 0.18) 1.0 + 0.012 * sin(2 * PI * 6.0 * t) else 1.0
                    ph += f * vib / sr; ph -= floor(ph)
                    val env = adsr(t, dur, 0.004, 0.08, 0.7, rel)
                    // 4-bit volume steps, like the old sound chips.
                    val v = floor(env * vel * 15) / 15
                    (if (ph < duty) 1.0 else -1.0) * 0.16 * v
                }
            }
            Instrument.TRIANGLE_8BIT -> {
                var ph = 0.0
                loop(buf, start, dur + 0.02) { t ->
                    ph += f / sr; ph -= floor(ph)
                    val tri = 1.0 - 4.0 * abs(ph - 0.5)
                    val q = floor(tri * 8) / 8 // 16-step triangle
                    q * 0.32 * vel * adsr(t, dur, 0.002, 0.01, 1.0, 0.02)
                }
            }
            Instrument.SAW_LEAD -> {
                var p1 = 0.0; var p2 = 0.37; var y1 = 0.0; var y2 = 0.0
                val f2 = f * 1.006
                loop(buf, start, dur + 0.25) { t ->
                    val vib = if (t > 0.25) 1.0 + 0.004 * sin(2 * PI * 5.5 * t) else 1.0
                    val dt1 = f * vib / sr; val dt2 = f2 * vib / sr
                    p1 += dt1; p1 -= floor(p1); p2 += dt2; p2 -= floor(p2)
                    val x = (saw(p1, dt1) + saw(p2, dt2)) * 0.5
                    val cut = lpCoef(1200.0 + 4000.0 * exp(-t * 3))
                    y1 += cut * (x - y1); y2 += cut * (y1 - y2)
                    y2 * 0.3 * vel * adsr(t, dur, 0.01, 0.2, 0.75, 0.25)
                }
            }
            Instrument.SAW_PAD -> {
                val ph = doubleArrayOf(0.0, 0.33, 0.66)
                val det = doubleArrayOf(1.0, 1.007, 0.993)
                var y = 0.0
                val c = lpCoef(1600.0)
                loop(buf, start, dur + 0.6) { t ->
                    var x = 0.0
                    for (i in 0 until 3) {
                        val dt = f * det[i] / sr
                        ph[i] += dt; ph[i] -= floor(ph[i])
                        x += saw(ph[i], dt)
                    }
                    y += c * (x / 3 - y)
                    y * 0.11 * vel * adsr(t, dur, 0.25, 0.3, 0.8, 0.6)
                }
            }
            Instrument.SYNTH_BASS -> {
                var p = 0.0; var y1 = 0.0; var y2 = 0.0
                loop(buf, start, dur + 0.05) { t ->
                    val dt = f / sr
                    p += dt; p -= floor(p)
                    val x = saw(p, dt)
                    val cut = lpCoef(300.0 + 1800.0 * exp(-t * 18))
                    y1 += cut * (x - y1); y2 += cut * (y1 - y2)
                    y2 * 0.42 * vel * adsr(t, dur, 0.003, 0.1, 0.7, 0.05)
                }
            }
            Instrument.LEAD_GUITAR, Instrument.POWER_GUITAR -> {
                val lead = ev.inst == Instrument.LEAD_GUITAR
                var p1 = 0.0; var p2 = 0.5; var y1 = 0.0; var y2 = 0.0; var hp = 0.0
                val c = lpCoef(if (lead) 3200.0 else 2200.0)
                val hpc = lpCoef(120.0)
                val rel = if (lead) 0.12 else 0.04
                loop(buf, start, dur + rel) { t ->
                    val bend = if (lead && t > 0.3) 1.0 + 0.008 * sin(2 * PI * 5.0 * t) else 1.0
                    val dt = f * bend / sr
                    p1 += dt; p1 -= floor(p1)
                    p2 += dt * 1.003; p2 -= floor(p2)
                    var x = saw(p1, dt) * 0.6 + (if (p2 < 0.5) 0.4 else -0.4)
                    x = tanh(x * (if (lead) 7.0 else 5.0)) // overdrive
                    y1 += c * (x - y1); y2 += c * (y1 - y2)
                    hp += hpc * (y2 - hp)
                    val env = if (lead) adsr(t, dur, 0.005, 0.3, 0.8, rel)
                    else adsr(t, dur, 0.003, 0.12, 0.45, rel)
                    (y2 - hp) * (if (lead) 0.2 else 0.12) * vel * env
                }
            }
            Instrument.PICK_BASS, Instrument.UPRIGHT_BASS -> karplus(buf, start, f, dur, vel, ev.inst == Instrument.UPRIGHT_BASS)
            Instrument.EPIANO -> {
                var pc = 0.0; var pm = 0.0
                loop(buf, start, dur + 0.4) { t ->
                    pc += f / sr; pm += f / sr
                    val idx = 1.6 * exp(-t * 5.0) + 0.15
                    val mod = idx * sin(2 * PI * pm)
                    val tine = 0.12 * exp(-t * 18) * sin(2 * PI * pc * 7.0)
                    val amp = exp(-t * 1.1) * adsr(t, dur, 0.003, 0.0001, 1.0, 0.4)
                    val trem = 1.0 + 0.12 * sin(2 * PI * 4.5 * t)
                    (sin(2 * PI * pc + mod) + tine) * 0.2 * vel * amp * trem
                }
            }
            Instrument.SUB_BASS -> {
                var p = 0.0
                loop(buf, start, dur + 0.08) { t ->
                    p += f / sr
                    val x = sin(2 * PI * p) + 0.18 * sin(4 * PI * p)
                    x * 0.42 * vel * adsr(t, dur, 0.01, 0.2, 0.8, 0.08)
                }
            }
            Instrument.VIBES -> {
                var p = 0.0
                loop(buf, start, dur + 0.8) { t ->
                    p += f / sr
                    val x = sin(2 * PI * p) + 0.35 * exp(-t * 6) * sin(2 * PI * p * 4.0) +
                        0.1 * exp(-t * 12) * sin(2 * PI * p * 10.0)
                    val trem = 1.0 + 0.2 * sin(2 * PI * 5.5 * t)
                    x * 0.22 * vel * exp(-t * 1.4) * trem * adsr(t, dur, 0.002, 0.0001, 1.0, 0.8)
                }
            }
            Instrument.PIANO -> {
                var p = 0.0
                val amps = doubleArrayOf(1.0, 0.5, 0.3, 0.18, 0.1, 0.06)
                loop(buf, start, dur + 0.3) { t ->
                    p += f / sr
                    var x = 0.0
                    for (h in amps.indices) {
                        val n = h + 1
                        val inh = 1.0 + 0.0004 * n * n
                        x += amps[h] * exp(-t * (1.2 + n * 0.9)) * sin(2 * PI * p * n * inh)
                    }
                    x * 0.16 * vel * adsr(t, dur, 0.002, 0.0001, 1.0, 0.3)
                }
            }
            Instrument.MUSIC_BOX -> {
                var p = 0.0
                loop(buf, start, max(dur, 1.6)) { t ->
                    p += f / sr
                    val idx = 2.0 * exp(-t * 9)
                    val x = sin(2 * PI * p + idx * sin(2 * PI * p * 3.5)) + 0.25 * exp(-t * 4) * sin(2 * PI * p * 2.0)
                    val att = min(1.0, t / 0.002)
                    x * 0.2 * vel * exp(-t * 2.4) * att
                }
            }
        }
    }

    private fun karplus(buf: FloatArray, start: Int, f: Double, dur: Double, vel: Double, upright: Boolean) {
        val period = max(2, (sr / f).toInt())
        val line = DoubleArray(period) { rng.nextDouble(-1.0, 1.0) }
        // Pre-filter the excitation to soften it.
        for (pass in 0 until if (upright) 3 else 1) {
            for (i in 1 until period) line[i] = 0.5 * (line[i] + line[i - 1])
        }
        var idx = 0
        val damp = if (upright) 0.994 else 0.997
        val rel = 0.06
        loop(buf, start, dur + rel) { t ->
            val cur = line[idx]
            val nxt = line[(idx + 1) % period]
            line[idx] = damp * 0.5 * (cur + nxt)
            idx = (idx + 1) % period
            cur * (if (upright) 0.75 else 0.6) * vel * adsr(t, dur, 0.001, 0.0001, 1.0, rel)
        }
    }

    private inline fun loop(buf: FloatArray, start: Int, lengthSec: Double, gen: (Double) -> Double) {
        val n = (lengthSec * sr).toInt()
        val end = min(buf.size, start + n)
        var i = max(0, start)
        while (i < end) {
            val t = (i - start).toDouble() / sr
            buf[i] += gen(t).toFloat()
            i++
        }
    }

    // ------------------------------------------------------------------ drums

    private var lfsr = 1

    private fun lfsrNoise(short: Boolean): Double {
        val bit = (lfsr xor (lfsr shr (if (short) 6 else 1))) and 1
        lfsr = (lfsr shr 1) or (bit shl 14)
        return if (lfsr and 1 == 1) 1.0 else -1.0
    }

    private fun renderDrum(buf: FloatArray, ev: DrumEvent) {
        val start = (ev.startSec * sr).toInt()
        val v = ev.vel.toDouble()
        when (ev.drum) {
            Drum.KICK -> {
                var p = 0.0
                loop(buf, start, 0.45) { t ->
                    val f = 45.0 + 110.0 * exp(-t * 35)
                    p += f / sr
                    val click = if (t < 0.003) 0.4 else 0.0
                    (sin(2 * PI * p) * exp(-t * 7) + click) * 0.75 * v
                }
            }
            Drum.SNARE, Drum.CLAP -> {
                var p = 0.0; var lp = 0.0
                val c = lpCoef(6000.0)
                val clap = ev.drum == Drum.CLAP
                loop(buf, start, 0.3) { t ->
                    p += 185.0 / sr
                    val n = rng.nextDouble(-1.0, 1.0)
                    lp += c * (n - lp)
                    val burst = if (clap) {
                        val k = (t / 0.011).toInt()
                        if (k < 3) exp(-(t - k * 0.011) * 250) else exp(-(t - 0.033) * 18)
                    } else exp(-t * 20)
                    val tone = if (clap) 0.0 else sin(2 * PI * p) * exp(-t * 30) * 0.5
                    (lp * burst + tone) * 0.45 * v
                }
            }
            Drum.HAT, Drum.OPEN_HAT, Drum.CRASH, Drum.RIDE -> {
                var lp = 0.0
                val c = lpCoef(7000.0)
                val decay = when (ev.drum) { Drum.HAT -> 55.0; Drum.OPEN_HAT -> 9.0; Drum.CRASH -> 2.2; else -> 5.0 }
                val len = when (ev.drum) { Drum.HAT -> 0.08; Drum.OPEN_HAT -> 0.4; Drum.CRASH -> 2.0; else -> 0.9 }
                var p1 = 0.0; var p2 = 0.0
                loop(buf, start, len) { t ->
                    val n = rng.nextDouble(-1.0, 1.0)
                    lp += c * (n - lp)
                    var x = n - lp // high-pass
                    if (ev.drum == Drum.RIDE) {
                        p1 += 3150.0 / sr; p2 += 4730.0 / sr
                        x = x * 0.5 + 0.3 * ((if (p1 % 1 < 0.5) 1 else -1) + (if (p2 % 1 < 0.5) 1 else -1))
                    }
                    val gain = when (ev.drum) { Drum.CRASH -> 0.22; Drum.RIDE -> 0.12; else -> 0.25 }
                    x * exp(-t * decay) * gain * v
                }
            }
            Drum.CHIP_KICK -> {
                var p = 0.0
                loop(buf, start, 0.15) { t ->
                    val f = 60.0 + 220.0 * exp(-t * 40)
                    p += f / sr; p -= floor(p)
                    val tri = floor((1.0 - 4.0 * abs(p - 0.5)) * 8) / 8
                    tri * 0.45 * v * (1 - t / 0.15)
                }
            }
            Drum.CHIP_SNARE, Drum.CHIP_HAT -> {
                val short = ev.drum == Drum.CHIP_HAT
                val len = if (short) 0.05 else 0.16
                var hold = 0.0; var cnt = 0
                val stride = if (short) 2 else 6
                loop(buf, start, len) { t ->
                    if (cnt++ % stride == 0) hold = lfsrNoise(short)
                    val env = floor((1 - t / len) * 15) / 15
                    hold * (if (short) 0.1 else 0.2) * v * env
                }
            }
        }
    }

    // ------------------------------------------------------------------ master

    private fun applyFx(buf: FloatArray, fx: MasterFx) {
        if (fx.delayMix > 0 && fx.delaySec > 0) {
            val d = (fx.delaySec * sr).toInt()
            val line = FloatArray(d)
            var idx = 0
            for (i in buf.indices) {
                val delayed = line[idx]
                line[idx] = (buf[i] + delayed * fx.delayFeedback).toFloat()
                buf[i] = (buf[i] + delayed * fx.delayMix).toFloat()
                idx = (idx + 1) % d
            }
        }
        if (fx.lowpassHz > 0) {
            val c = lpCoef(fx.lowpassHz)
            var y = 0.0
            for (i in buf.indices) { y += c * (buf[i] - y); buf[i] = y.toFloat() }
        }
        if (fx.crackle) {
            var hiss = 0.0
            for (i in buf.indices) {
                hiss += 0.05 * (rng.nextDouble(-1.0, 1.0) - hiss)
                var x = buf[i] + hiss * 0.02
                if (rng.nextDouble() < 0.0004) x += rng.nextDouble(-0.25, 0.25)
                buf[i] = x.toFloat()
            }
        }
        // Normalise + soft clip.
        var peak = 0f
        for (s in buf) peak = max(peak, abs(s))
        if (peak > 0) {
            val g = 0.9f / peak * 1.3f
            for (i in buf.indices) buf[i] = (tanh((buf[i] * g).toDouble()) * 0.92).toFloat()
        }
        if (fx.bitCrush) {
            for (i in buf.indices) buf[i] = Math.round(buf[i] * 48f) / 48f
        }
    }

    /** Converts float samples to 16-bit PCM. */
    companion object {
        fun toPcm16(buf: FloatArray): ShortArray =
            ShortArray(buf.size) { (buf[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
    }
}
