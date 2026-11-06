package com.whistlesong.music

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

enum class SectionKind(val label: String) {
    YOURS("Your whistle"),
    ANSWER("Answer"),
    DEVELOP("New idea"),
    RETURN("Return"),
}

data class Section(val kind: SectionKind, val startBar: Int, val bars: Int)

/** A finished composition, ready for arranging. */
data class Song(
    val key: Key,
    val bpm: Double,
    val beatsPerBar: Int,
    val melody: List<Note>,
    /** One chord per bar. */
    val chords: List<Chord>,
    val sections: List<Section>,
    val style: Style,
    val userBars: Int,
) {
    val totalBars: Int get() = chords.size
    val totalBeats: Double get() = totalBars * beatsPerBar.toDouble()
    val beatSec: Double get() = 60.0 / bpm
}

/**
 * The "AI" composer.
 *
 * It learns a small variable-order Markov model of melodic intervals and rhythms from the
 * whistled phrase, blends it with a style-specific prior and music-theory constraints
 * (chord tones on strong beats, leap recovery, range, cadences), and develops the user's
 * motif using classic compositional devices: answer phrases, sequences, inversion,
 * fresh contrasting material, and a return of the theme with a final cadence.
 *
 * Everything runs on-device and is deterministic for a given seed, so "New idea" just
 * means "new seed".
 */
class Composer(private val style: Style, seed: Long) {
    private val rng = Random(seed)
    private val bpb = 4

    // Markov tables over intervals (scale steps).
    private val order1 = HashMap<Int, HashMap<Int, Double>>()
    private val order2 = HashMap<Pair<Int, Int>, HashMap<Int, Double>>()
    private val unigram = HashMap<Int, Double>()

    private var lo = 0
    private var hi = 0
    private var center = 0
    private lateinit var key: Key

    fun compose(t: Transcription, continuationBars: Int): Song {
        key = t.key
        val input = Transcriber.fitOctave(t.notes, style.melodyCenter)
        val lastEnd = input.maxOf { it.end }
        val k = max(1, ceil(lastEnd / bpb - 0.01).toInt())

        // Degrees of the input.
        val inDeg = input.map { key.midiToNearestDegree(it.pitch.toDouble()) }
        train(inDeg)
        lo = inDeg.min() - 2
        hi = inDeg.max() + 2
        center = inDeg.sorted()[inDeg.size / 2]
        if (hi - lo < 9) {
            val pad = (9 - (hi - lo) + 1) / 2
            lo -= pad; hi += pad
        }

        val userBarRhythms = (0 until k).map { b ->
            input.filter { it.start >= b * bpb && it.start < (b + 1) * bpb }
                .map { (it.start - b * bpb) to it.duration }
        }.filter { it.isNotEmpty() }

        // Phrase plan.
        val sections = ArrayList<Section>()
        sections += Section(SectionKind.YOURS, 0, k)
        val cycle = listOf(SectionKind.ANSWER, SectionKind.DEVELOP, SectionKind.RETURN)
        var bar = k
        var ci = 0
        val total = k + continuationBars
        while (bar < total) {
            val len = min(k, total - bar)
            sections += Section(cycle[ci % cycle.size], bar, len)
            bar += len; ci++
        }

        val melody = ArrayList<Note>(input)
        val chords = arrayOfNulls<Chord>(total)
        // Harmonise the user's bars.
        harmonise(melody, 0, k, chords)

        for ((si, sec) in sections.withIndex()) {
            if (sec.kind == SectionKind.YOURS) continue
            val isFinal = si == sections.lastIndex
            val offset = sec.startBar * bpb.toDouble()
            val endBeat = (sec.startBar + sec.bars) * bpb.toDouble()
            val newNotes: List<Note> = when (sec.kind) {
                SectionKind.ANSWER -> answer(input, inDeg, offset, endBeat, melody)
                SectionKind.RETURN -> restate(input, offset, endBeat, melody)
                SectionKind.DEVELOP -> {
                    val prog = style.progressions(key.mode).random(rng)
                    for (b in 0 until sec.bars) {
                        chords[sec.startBar + b] = Chord(key, prog[b % prog.size], style.useSevenths)
                    }
                    develop(sec, userBarRhythms, chords, melody)
                }
                else -> emptyList()
            }
            melody += newNotes
            if (sec.kind != SectionKind.DEVELOP) harmonise(melody, sec.startBar, sec.startBar + sec.bars, chords)

            if (isFinal) cadence(melody, total, chords)
            else if (sec.kind == SectionKind.ANSWER) halfCadence(melody, endBeat)
        }

        // Fill any gaps (shouldn't happen) with tonic.
        for (i in chords.indices) if (chords[i] == null) chords[i] = Chord(key, 0, style.useSevenths)

        var finalMelody = melody.sortedBy { it.start }
        if (style == Style.JAZZ) finalMelody = jazzify(finalMelody)

        return Song(
            key = key,
            bpm = fitTempo(t.bpm),
            beatsPerBar = bpb,
            melody = finalMelody,
            chords = chords.map { it!! },
            sections = sections,
            style = style,
            userBars = k,
        )
    }

    private fun fitTempo(bpm: Double): Double {
        var b = bpm
        while (b < style.minBpm && b * 2 <= style.maxBpm * 1.15) b *= 2
        while (b > style.maxBpm && b / 2 >= style.minBpm * 0.85) b /= 2
        return b.coerceIn(style.minBpm, style.maxBpm)
    }

    // ---------------------------------------------------------------- learning

    private fun train(deg: List<Int>) {
        val iv = deg.zipWithNext { a, b -> (b - a).coerceIn(-7, 7) }
        for (i in iv.indices) {
            unigram.merge(iv[i], 1.0, Double::plus)
            if (i >= 1) order1.getOrPut(iv[i - 1]) { HashMap() }.merge(iv[i], 1.0, Double::plus)
            if (i >= 2) order2.getOrPut(iv[i - 2] to iv[i - 1]) { HashMap() }.merge(iv[i], 1.0, Double::plus)
        }
    }

    private fun userProb(history: List<Int>, iv: Int): Double {
        fun p(table: Map<Int, Double>?): Double? {
            if (table == null || table.isEmpty()) return null
            return (table[iv] ?: 0.0) / table.values.sum()
        }
        val ivs = history.zipWithNext { a, b -> b - a }
        val p2 = if (ivs.size >= 2) p(order2[ivs[ivs.size - 2] to ivs.last()]) else null
        val p1 = if (ivs.isNotEmpty()) p(order1[ivs.last()]) else null
        val p0 = p(unigram) ?: 0.0
        return when {
            p2 != null && p1 != null -> 0.5 * p2 + 0.3 * p1 + 0.2 * p0
            p1 != null -> 0.7 * p1 + 0.3 * p0
            else -> p0
        }
    }

    // ---------------------------------------------------------------- pitch choice

    private fun deg(n: Note) = key.midiToNearestDegree(n.pitch.toDouble())

    private fun choosePitch(history: List<Int>, beatInBar: Double, chord: Chord, temperature: Double = 1.0): Int {
        val last = history.last()
        val prevIv = if (history.size >= 2) history.last() - history[history.size - 2] else 0
        val avoid = style.avoid(key.mode)
        val prior = style.intervalPrior
        val priorSum = prior.sum() * 2 - prior[0]
        val strong = abs(beatInBar % 2.0) < 1e-6
        val onBeat = abs(beatInBar - Math.round(beatInBar)) < 1e-6

        val cands = (last - 7..last + 7).filter { it in lo..hi }
        val weights = DoubleArray(cands.size)
        for ((i, c) in cands.withIndex()) {
            val iv = c - last
            val pStyle = prior[abs(iv)] / priorSum
            var w = 0.55 * userProb(history, iv) + 0.45 * pStyle + 1e-4
            val midi = key.degreeToMidi(c)
            if (chord.containsMidi(midi)) {
                w *= if (strong) style.chordToneBias else if (onBeat) sqrt(style.chordToneBias) else 1.0
            } else if (strong) {
                w *= 0.6
            }
            if (strong && Math.floorMod(c, 7) in avoid) w *= 0.3
            // Leap recovery: after a big leap, step back the other way.
            if (abs(prevIv) >= 3) {
                if (sign(iv.toDouble()) == sign(prevIv.toDouble())) w *= 0.3
                else if (abs(iv) in 1..2) w *= 1.6
            }
            // Don't get stuck on one note.
            if (iv == 0 && prevIv == 0) w *= 0.3
            // Gentle gravity toward the melody's centre.
            val d = (c - center).toDouble()
            w *= exp(-(d * d) / (2 * 4.5 * 4.5))
            weights[i] = w.pow(1.0 / temperature)
        }
        return cands[sample(weights)]
    }

    private fun sample(w: DoubleArray): Int {
        val total = w.sum()
        var r = rng.nextDouble() * total
        for (i in w.indices) {
            r -= w[i]
            if (r <= 0) return i
        }
        return w.lastIndex
    }

    private fun historyBefore(melody: List<Note>, beat: Double, n: Int = 4): MutableList<Int> =
        melody.filter { it.start < beat }.sortedBy { it.start }.takeLast(n).map { deg(it) }.toMutableList()
            .ifEmpty { mutableListOf(center) }

    // ---------------------------------------------------------------- sections

    /** Consequent phrase: the motif transformed (sequence / new ending / inversion). */
    private fun answer(input: List<Note>, inDeg: List<Int>, offset: Double, endBeat: Double, melody: List<Note>): List<Note> {
        val r = rng.nextDouble()
        val out = ArrayList<Note>()
        when {
            r < 0.4 -> {
                // Diatonic sequence: same shape, shifted by a step or a third.
                val shifts = listOf(1, -1, 2, -2).filter { s -> inDeg.all { it + s in lo..hi } }
                val s = if (shifts.isEmpty()) 0 else shifts.random(rng)
                for ((i, n) in input.withIndex()) {
                    out += n.copy(pitch = key.degreeToMidi(inDeg[i] + s), start = n.start + offset, fromUser = false)
                }
            }
            r < 0.8 -> {
                // Keep the first half, compose a new second half with the same rhythm.
                val half = input.size / 2
                val hist = historyBefore(melody, offset)
                for ((i, n) in input.withIndex()) {
                    val start = n.start + offset
                    if (i < max(1, half)) {
                        out += n.copy(start = start, fromUser = false)
                        hist += inDeg[i]
                    } else {
                        val chord = Chord(key, 0)
                        val beatInBar = start % bpb
                        val d = choosePitch(hist, beatInBar, guessChordFor(n, input, chord))
                        hist += d
                        out += n.copy(pitch = key.degreeToMidi(d), start = start, fromUser = false)
                    }
                }
            }
            else -> {
                // Inversion around the first note.
                val axis = inDeg.first()
                for ((i, n) in input.withIndex()) {
                    val d = (2 * axis - inDeg[i]).coerceIn(lo, hi)
                    out += n.copy(pitch = key.degreeToMidi(d), start = n.start + offset, fromUser = false)
                }
            }
        }
        return out.filter { it.start < endBeat }.map { if (it.end > endBeat) it.copy(duration = endBeat - it.start) else it }
    }

    /** Pick a plausible chord for a note position by looking at what the user played in that bar. */
    private fun guessChordFor(n: Note, input: List<Note>, fallback: Chord): Chord {
        val bar = (n.start / bpb).toInt()
        val barNotes = input.filter { (it.start / bpb).toInt() == bar }
        return bestChord(barNotes, null) ?: fallback
    }

    /** Restatement of the theme, occasionally with a re-composed final bar. */
    private fun restate(input: List<Note>, offset: Double, endBeat: Double, melody: List<Note>): List<Note> {
        val out = input.map { it.copy(start = it.start + offset, fromUser = false) }
            .filter { it.start < endBeat }
            .map { if (it.end > endBeat) it.copy(duration = endBeat - it.start) else it }
            .toMutableList()
        val lastBarStart = endBeat - bpb
        if (rng.nextDouble() < 0.35 && out.any { it.start >= lastBarStart } && lastBarStart > offset) {
            val hist = historyBefore(melody + out, lastBarStart)
            for (i in out.indices) {
                if (out[i].start >= lastBarStart) {
                    val chord = bestChord(out.filter { it.start >= lastBarStart }, null) ?: Chord(key, 0)
                    val d = choosePitch(hist, out[i].start % bpb, chord, 0.8)
                    hist += d
                    out[i] = out[i].copy(pitch = key.degreeToMidi(d))
                }
            }
        }
        return out
    }

    /** Brand-new contrasting material over a style progression. */
    private fun develop(
        sec: Section,
        userRhythms: List<List<Pair<Double, Double>>>,
        chords: Array<Chord?>,
        melody: List<Note>,
    ): List<Note> {
        val out = ArrayList<Note>()
        val hist = historyBefore(melody, sec.startBar * bpb.toDouble())
        var prevBar: List<Note>? = null
        for (b in 0 until sec.bars) {
            val barStart = (sec.startBar + b) * bpb.toDouble()
            val chord = chords[sec.startBar + b]!!

            // Motivic sequence: repeat the previous bar a step higher/lower.
            if (prevBar != null && prevBar.isNotEmpty() && rng.nextDouble() < 0.3) {
                val s = if (rng.nextBoolean()) 1 else -1
                val moved = prevBar.map {
                    val d = (deg(it) + s).coerceIn(lo, hi)
                    it.copy(pitch = key.degreeToMidi(d), start = it.start + bpb)
                }
                out += moved
                moved.forEach { hist += deg(it) }
                prevBar = moved
                continue
            }

            val rhythm = mutateRhythm(
                if (userRhythms.isNotEmpty() && rng.nextDouble() < 0.7) userRhythms.random(rng) else styleRhythm()
            )
            val barNotes = ArrayList<Note>()
            for ((on, dur) in rhythm) {
                val d = choosePitch(hist, on, chord)
                hist += d
                barNotes += Note(key.degreeToMidi(d), barStart + on, dur, velocity = 0.8f)
            }
            out += barNotes
            prevBar = barNotes
        }
        return out
    }

    private fun styleRhythm(): List<Pair<Double, Double>> {
        val patterns: List<List<Pair<Double, Double>>> = when (style) {
            Style.CHIPTUNE -> listOf(
                listOf(0.0 to 0.5, 0.5 to 0.5, 1.0 to 0.5, 1.5 to 0.5, 2.0 to 1.0, 3.0 to 1.0),
                listOf(0.0 to 0.75, 0.75 to 0.25, 1.0 to 0.5, 1.5 to 0.5, 2.0 to 2.0),
            )
            Style.ROCK -> listOf(
                listOf(0.0 to 1.0, 1.0 to 0.5, 1.5 to 1.0, 2.5 to 1.5),
                listOf(0.0 to 0.5, 0.5 to 0.5, 1.0 to 1.0, 2.0 to 2.0),
            )
            Style.LOFI -> listOf(
                listOf(0.5 to 1.0, 1.5 to 0.5, 2.0 to 2.0),
                listOf(0.0 to 1.5, 1.5 to 2.5),
            )
            Style.SYNTHWAVE -> listOf(
                listOf(0.0 to 1.5, 1.5 to 1.5, 3.0 to 1.0),
                listOf(0.0 to 0.5, 0.5 to 0.5, 1.0 to 1.0, 2.0 to 1.5, 3.5 to 0.5),
            )
            Style.JAZZ -> listOf(
                listOf(0.0 to 0.5, 0.5 to 0.5, 1.0 to 0.5, 1.5 to 0.5, 2.0 to 0.5, 2.5 to 1.5),
                listOf(0.5 to 0.5, 1.0 to 0.5, 1.5 to 1.0, 2.5 to 0.5, 3.0 to 1.0),
            )
            Style.MUSIC_BOX -> listOf(
                listOf(0.0 to 1.0, 1.0 to 1.0, 2.0 to 2.0),
                listOf(0.0 to 1.5, 1.5 to 0.5, 2.0 to 1.0, 3.0 to 1.0),
            )
        }
        return patterns.random(rng)
    }

    /** Small random edits so new bars feel related to — but not copies of — the whistle. */
    private fun mutateRhythm(r: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val out = r.toMutableList()
        val dens = style.rhythmDensity
        if (dens > 0 && rng.nextDouble() < dens) {
            val idx = out.indices.filter { out[it].second >= 1.0 }.randomOrNull(rng)
            if (idx != null) {
                val (on, d) = out[idx]
                out[idx] = on to d / 2
                out.add(idx + 1, on + d / 2 to d / 2)
            }
        } else if (dens < 0 && rng.nextDouble() < -dens && out.size >= 2) {
            val idx = rng.nextInt(out.size - 1)
            val (on, _) = out[idx]
            val (on2, d2) = out[idx + 1]
            out[idx] = on to (on2 + d2 - on)
            out.removeAt(idx + 1)
        }
        return out.filter { it.first < bpb }.map { (on, d) -> on to min(d, bpb - on) }
    }

    // ---------------------------------------------------------------- endings

    private fun halfCadence(melody: MutableList<Note>, endBeat: Double) {
        val idx = melody.indices.filter { melody[it].start < endBeat }.maxByOrNull { melody[it].start } ?: return
        val n = melody[idx]
        if (n.fromUser) return
        // Land on scale degree 5 (dominant) or 2 — whichever is closest.
        val d = deg(n)
        val target = nearestWithPc(d, setOf(4, 1))
        melody[idx] = n.copy(pitch = key.degreeToMidi(target))
    }

    private fun cadence(melody: MutableList<Note>, totalBars: Int, chords: Array<Chord?>) {
        val finalBar = (totalBars - 1) * bpb.toDouble()
        melody.removeAll { it.start >= finalBar && !it.fromUser }
        if (melody.any { it.start >= finalBar }) return // user's own melody already ends here
        // Clip anything ringing into the final bar.
        for (i in melody.indices) {
            val n = melody[i]
            if (n.end > finalBar) melody[i] = n.copy(duration = finalBar - n.start)
        }
        val prevIdx = melody.indices.maxByOrNull { melody[it].start }
        var approach = center
        if (prevIdx != null && !melody[prevIdx].fromUser) {
            val p = melody[prevIdx]
            val d = nearestWithPc(deg(p), setOf(1, 6, 4))
            melody[prevIdx] = p.copy(pitch = key.degreeToMidi(d))
            approach = d
        } else if (prevIdx != null) {
            approach = deg(melody[prevIdx])
        }
        val tonic = nearestWithPc(approach, setOf(0))
        melody += Note(key.degreeToMidi(tonic), finalBar, bpb - 1.0, velocity = 0.85f)
        chords[totalBars - 1] = Chord(key, 0, style.useSevenths && style == Style.LOFI)
        if (totalBars >= 2 && chords.size >= 2) chords[totalBars - 2] = Chord(key, 4, style.useSevenths)
    }

    private fun nearestWithPc(fromDeg: Int, degreeClasses: Set<Int>): Int {
        var best = fromDeg
        var bestDist = Int.MAX_VALUE
        for (d in fromDeg - 6..fromDeg + 6) {
            if (Math.floorMod(d, 7) in degreeClasses && abs(d - fromDeg) < bestDist && d in (lo - 2)..(hi + 2)) {
                best = d; bestDist = abs(d - fromDeg)
            }
        }
        return best
    }

    // ---------------------------------------------------------------- harmony

    private fun harmonise(melody: List<Note>, fromBar: Int, toBar: Int, chords: Array<Chord?>) {
        for (b in fromBar until toBar) {
            if (b >= chords.size) break
            val barNotes = melody.filter { it.start >= b * bpb && it.start < (b + 1) * bpb }
            val prev = if (b > 0) chords[b - 1] else null
            chords[b] = bestChord(barNotes, prev) ?: prev ?: Chord(key, 0, style.useSevenths)
        }
    }

    private fun bestChord(barNotes: List<Note>, prev: Chord?): Chord? {
        if (barNotes.isEmpty()) return null
        val diminished = if (key.mode == Mode.MAJOR) 6 else 1
        val degreePrior = doubleArrayOf(0.35, 0.1, 0.05, 0.25, 0.25, 0.2, 0.0)
        var best: Chord? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (d in 0..6) {
            if (d == diminished) continue
            val c = Chord(key, d, style.useSevenths)
            val triad = Chord(key, d, false)
            var score = degreePrior[d]
            for (n in barNotes) {
                val strong = abs((n.start % bpb) % 2.0) < 1e-6
                val w = n.duration * (if (strong) 1.5 else 1.0)
                score += when {
                    triad.containsMidi(n.pitch) -> w
                    c.containsMidi(n.pitch) -> 0.6 * w
                    else -> -0.4 * w
                }
            }
            if (prev != null && prev.degree == d) score -= 0.05
            if (score > bestScore) { bestScore = score; best = c }
        }
        return best
    }

    // ---------------------------------------------------------------- flavour

    /** Adds chromatic approach tones before strong-beat notes. */
    private fun jazzify(m: List<Note>): List<Note> {
        val out = m.toMutableList()
        for (i in 0 until out.size - 1) {
            val n = out[i]; val next = out[i + 1]
            if (n.fromUser) continue
            val nextStrong = abs(next.start % 1.0) < 1e-6
            if (nextStrong && n.duration <= 0.5 && abs(next.start - n.end) < 1e-6 && rng.nextDouble() < 0.35) {
                val dir = if (n.pitch > next.pitch) 1 else -1
                out[i] = n.copy(pitch = next.pitch + dir)
            }
        }
        return out
    }
}
