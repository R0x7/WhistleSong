package com.whistlesong.music

import com.whistlesong.dsp.PitchFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A note as heard in the recording, before autotune / rhythm quantisation. */
data class RawNote(val startSec: Double, val endSec: Double, val midi: Double) {
    val durSec get() = endSec - startSec
}

/** The whistle after autotune: notes on a beat grid in a detected key at a detected tempo. */
data class Transcription(
    val rawNotes: List<RawNote>,
    val key: Key,
    val bpm: Double,
    /** Quantised & key-snapped notes, starting at beat 0, at the whistled octave. */
    val notes: List<Note>,
    /** Global tuning offset that was removed, in cents. */
    val tuningCents: Double,
)

object Transcriber {

    /** Turns a pitch track into discrete notes. */
    fun segment(frames: List<PitchFrame>): List<RawNote> {
        if (frames.isEmpty()) return emptyList()
        val hop = if (frames.size > 1) frames[1].timeSec - frames[0].timeSec else 0.01

        // Adaptive silence gate: relative to the loud part of the recording.
        val voicedRms = frames.filter { it.hz != null }.map { it.rms }.sorted()
        if (voicedRms.isEmpty()) return emptyList()
        val loud = voicedRms[(voicedRms.size * 0.9).toInt().coerceAtMost(voicedRms.size - 1)]
        val gate = max(0.004, loud * 0.12)

        val midi = DoubleArray(frames.size) { Double.NaN }
        for (i in frames.indices) {
            val f = frames[i]
            if (f.hz != null && f.rms >= gate && f.confidence > 0.6) midi[i] = f.midi!!
        }
        // Median filter (5) over voiced frames to kill octave blips.
        val smooth = DoubleArray(frames.size) { Double.NaN }
        for (i in frames.indices) {
            if (midi[i].isNaN()) continue
            val win = (max(0, i - 2)..min(frames.size - 1, i + 2)).map { midi[it] }.filter { !it.isNaN() }.sorted()
            smooth[i] = win[win.size / 2]
        }
        // Drop isolated voiced frames (need >= 3 consecutive).
        run {
            var i = 0
            while (i < smooth.size) {
                if (smooth[i].isNaN()) { i++; continue }
                var j = i
                while (j < smooth.size && !smooth[j].isNaN()) j++
                if (j - i < 3) for (k in i until j) smooth[k] = Double.NaN
                i = j
            }
        }

        // Walk voiced runs and split on stable pitch changes.
        val notes = ArrayList<RawNote>()
        var i = 0
        while (i < smooth.size) {
            if (smooth[i].isNaN()) { i++; continue }
            var j = i
            while (j < smooth.size && !smooth[j].isNaN()) j++
            // Voiced run [i, j)
            var noteStart = i
            var k = i + 1
            while (k < j) {
                // Re-articulation of the same pitch shows up as a dip in loudness.
                if (k - noteStart >= 5 && k + 3 < j && isLevelDip(frames, noteStart, k, j)) {
                    notes += makeNote(frames, smooth, noteStart, k, hop)
                    noteStart = k + 1
                    k += 2
                    continue
                }
                val ref = median(smooth, noteStart, k)
                // A change is "real" if the next 3 frames all sit > 0.6 st away on the same side.
                val need = 3
                if (k + need <= j) {
                    val diffs = (k until k + need).map { smooth[it] - ref }
                    val allUp = diffs.all { it > 0.6 }
                    val allDown = diffs.all { it < -0.6 }
                    if ((allUp || allDown) && k - noteStart >= 3) {
                        notes += makeNote(frames, smooth, noteStart, k, hop)
                        noteStart = k
                    }
                }
                k++
            }
            notes += makeNote(frames, smooth, noteStart, j, hop)
            i = j
        }

        // Remove very short blips, then merge same-pitch neighbours separated by tiny gaps.
        val filtered = notes.filter { it.durSec >= 0.06 }
        val merged = ArrayList<RawNote>()
        for (n in filtered) {
            val last = merged.lastOrNull()
            if (last != null && n.startSec - last.endSec < 0.012 && abs(n.midi - last.midi) < 0.5) {
                val w1 = last.durSec; val w2 = n.durSec
                merged[merged.size - 1] = RawNote(last.startSec, n.endSec, (last.midi * w1 + n.midi * w2) / (w1 + w2))
            } else merged += n
        }
        return merged
    }

    /** True if frame k is a local loudness minimum well below the levels on both sides. */
    private fun isLevelDip(frames: List<PitchFrame>, from: Int, k: Int, to: Int): Boolean {
        val r = frames[k].rms
        if (r > frames[k - 1].rms || r > frames[k + 1].rms) return false
        val before = (max(from, k - 15) until k).maxOf { frames[it].rms }
        val after = (k + 1 until min(to, k + 15)).maxOf { frames[it].rms }
        return r < 0.45 * before && r < 0.45 * after
    }

    private fun median(a: DoubleArray, from: Int, to: Int): Double {
        val s = (from until to).map { a[it] }.sorted()
        return s[s.size / 2]
    }

    private fun makeNote(frames: List<PitchFrame>, smooth: DoubleArray, from: Int, to: Int, hop: Double): RawNote {
        // Ignore the glide at the start/end of the note when estimating pitch.
        val len = to - from
        val trim = (len * 0.2).toInt()
        val a = from + trim
        val b = max(a + 1, to - trim)
        return RawNote(
            frames[from].timeSec - hop / 2,
            frames[to - 1].timeSec + hop / 2,
            median(smooth, a, b),
        )
    }

    /** Full "autotune": tuning correction, key detection, pitch snapping, tempo + rhythm quantisation. */
    fun transcribe(frames: List<PitchFrame>): Transcription? {
        val raw = segment(frames)
        if (raw.isEmpty()) return null

        // 1. Global tuning: people whistle consistently sharp/flat. Use a circular mean
        //    of the fractional part (duration weighted) and remove it.
        var sx = 0.0; var sy = 0.0
        for (n in raw) {
            val ang = 2 * PI * (n.midi - n.midi.roundToInt())
            sx += cos(ang) * n.durSec; sy += sin(ang) * n.durSec
        }
        val offset = atan2(sy, sx) / (2 * PI) // semitones in [-0.5, 0.5]
        val tuned = raw.map { it.copy(midi = it.midi - offset) }

        // 2. Key.
        val key = KeyFinder.find(tuned.map { it.midi to it.durSec }, tuned.last().midi)

        // 3. Tempo + grid.
        val onsets = tuned.map { it.startSec - tuned[0].startSec }
        val bpm = estimateTempo(onsets, tuned.map { it.durSec })
        val beatSec = 60.0 / bpm
        val sixteenth = 0.25

        fun q(sec: Double) = (sec / beatSec / sixteenth).roundToInt() * sixteenth

        val starts = DoubleArray(tuned.size)
        for (i in tuned.indices) {
            var s = q(onsets[i])
            if (i > 0 && s <= starts[i - 1]) s = starts[i - 1] + sixteenth
            starts[i] = s
        }
        val notes = ArrayList<Note>()
        for (i in tuned.indices) {
            val n = tuned[i]
            var dur = max(sixteenth, q(n.durSec))
            if (i + 1 < tuned.size) {
                val next = starts[i + 1]
                if (starts[i] + dur > next) dur = next - starts[i]
                // Fill tiny gaps (legato) — whistlers breathe between notes.
                if (next - (starts[i] + dur) <= 0.5) dur = next - starts[i]
            } else {
                dur = max(dur, 0.5)
            }
            notes += Note(key.snap(n.midi), starts[i], dur, fromUser = true)
        }
        return Transcription(raw, key, bpm, notes, offset * 100)
    }

    /**
     * Picks the tempo that best explains onsets on an eighth-note grid, with a gentle
     * prior toward ~110 BPM so we don't pick absurdly fast grids that fit anything.
     */
    fun estimateTempo(onsets: List<Double>, durations: List<Double>): Double {
        if (onsets.size < 3) {
            // Too few notes: assume the median note is an eighth (or a quarter if long).
            val d = durations.sorted()[durations.size / 2]
            var bpm = 60.0 / (d * 2)
            while (bpm < 70) bpm *= 2
            while (bpm > 160) bpm /= 2
            return bpm
        }
        var best = 110.0
        var bestScore = Double.MAX_VALUE
        var bpm = 60.0
        while (bpm <= 180.0) {
            val eighth = 30.0 / bpm
            var err = 0.0
            for (t in onsets) {
                val x = t / eighth
                err += abs(x - x.roundToInt())
            }
            err /= onsets.size
            val prior = abs(ln(bpm / 110.0) / ln(2.0)) * 0.12
            val score = err + prior
            if (score < bestScore) { bestScore = score; best = bpm }
            bpm += 0.5
        }
        return best
    }

    /** Shift a melody by octaves so its median sits near `center`. */
    fun fitOctave(notes: List<Note>, center: Int): List<Note> {
        if (notes.isEmpty()) return notes
        val med = notes.map { it.pitch }.sorted()[notes.size / 2]
        val shift = ((center - med) / 12.0).roundToInt() * 12
        return notes.map { it.copy(pitch = it.pitch + shift) }
    }
}
