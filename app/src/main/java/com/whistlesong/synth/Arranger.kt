package com.whistlesong.synth

import com.whistlesong.music.Chord
import com.whistlesong.music.Song
import com.whistlesong.music.Style
import kotlin.math.floor
import kotlin.random.Random

/** Turns a composed [Song] into instrument parts + drums for the chosen style. */
object Arranger {

    fun arrange(song: Song, seed: Long = 7): Arrangement {
        val rng = Random(seed)
        val bpb = song.beatsPerBar
        val beatSec = song.beatSec
        val swing = song.style.swing

        // Beat position -> seconds, with swing applied to off-beat eighths.
        fun sec(beat: Double): Double {
            val whole = floor(beat)
            val frac = beat - whole
            val sw = if (frac < 0.5) frac * (swing / 0.5) else swing + (frac - 0.5) * ((1 - swing) / 0.5)
            return (whole + sw) * beatSec
        }

        val notes = ArrayList<NoteEvent>()
        val drums = ArrayList<DrumEvent>()

        fun note(inst: Instrument, midi: Int, beat: Double, durBeats: Double, vel: Float) {
            val s = sec(beat)
            notes += NoteEvent(inst, midi, s, sec(beat + durBeats) - s, vel)
        }
        fun hit(d: Drum, beat: Double, vel: Float) { drums += DrumEvent(d, sec(beat), vel) }

        val style = song.style
        val leadInst = when (style) {
            Style.CHIPTUNE -> Instrument.PULSE_25
            Style.ROCK -> Instrument.LEAD_GUITAR
            Style.LOFI -> Instrument.EPIANO
            Style.SYNTHWAVE -> Instrument.SAW_LEAD
            Style.JAZZ -> Instrument.VIBES
            Style.MUSIC_BOX -> Instrument.MUSIC_BOX
        }

        // Lead melody (slightly louder on downbeats).
        for (n in song.melody) {
            val accent = if (n.start % bpb == 0.0) 1.0f else 0.92f
            note(leadInst, n.pitch, n.start, n.duration, (n.velocity * accent).coerceAtMost(1f))
        }

        // Section starts get a crash/fill.
        val sectionStarts = song.sections.map { it.startBar }.toSet()
        val lastBar = song.totalBars - 1

        for (bar in 0 until song.totalBars) {
            val b0 = bar * bpb.toDouble()
            val chord = song.chords[bar]
            val isLast = bar == lastBar
            // Let the user's part breathe a bit: lighter drums in the very first bar.
            val drumVel = if (bar == 0) 0.75f else 1f

            if (isLast) {
                finalBar(style, chord, b0, ::note, ::hit)
                continue
            }

            when (style) {
                Style.CHIPTUNE -> {
                    val root = chord.rootMidiNear(40)
                    for (i in 0 until bpb * 2) {
                        note(Instrument.TRIANGLE_8BIT, if (i % 2 == 0) root else root + 12, b0 + i * 0.5, 0.45, 0.9f)
                    }
                    val arp = chord.voicing(64)
                    for (i in 0 until bpb * 4) {
                        note(Instrument.PULSE_12, arp[i % arp.size] + 12, b0 + i * 0.25, 0.22, 0.35f)
                    }
                    hit(Drum.CHIP_KICK, b0, drumVel); hit(Drum.CHIP_KICK, b0 + 2.5, 0.8f * drumVel)
                    hit(Drum.CHIP_SNARE, b0 + 1, drumVel); hit(Drum.CHIP_SNARE, b0 + 3, drumVel)
                    for (i in 0 until 8) hit(Drum.CHIP_HAT, b0 + i * 0.5, 0.6f * drumVel)
                    if (bar % 4 == 3) for (i in 0 until 4) hit(Drum.CHIP_SNARE, b0 + 3 + i * 0.25, 0.7f)
                }
                Style.ROCK -> {
                    val root = chord.rootMidiNear(43)
                    for (i in 0 until bpb * 2) {
                        val b = b0 + i * 0.5
                        val v = if (i % 2 == 0) 0.9f else 0.75f
                        note(Instrument.POWER_GUITAR, root, b, 0.45, v)
                        note(Instrument.POWER_GUITAR, root + 7, b, 0.45, v)
                        note(Instrument.POWER_GUITAR, root + 12, b, 0.45, v * 0.8f)
                        note(Instrument.PICK_BASS, root - 12, b, 0.45, 0.95f)
                    }
                    if (bar in sectionStarts && bar > 0) hit(Drum.CRASH, b0, 0.9f)
                    hit(Drum.KICK, b0, drumVel); hit(Drum.KICK, b0 + 2, drumVel); hit(Drum.KICK, b0 + 2.5, 0.8f * drumVel)
                    hit(Drum.SNARE, b0 + 1, drumVel); hit(Drum.SNARE, b0 + 3, drumVel)
                    for (i in 0 until 8) hit(Drum.HAT, b0 + i * 0.5, (if (i % 2 == 0) 0.8f else 0.55f) * drumVel)
                    if (bar + 1 in sectionStarts) {
                        hit(Drum.SNARE, b0 + 3.5, 0.8f); hit(Drum.SNARE, b0 + 3.75, 0.9f)
                    }
                }
                Style.LOFI -> {
                    val v = chord.voicing(55)
                    for (p in v) {
                        note(Instrument.EPIANO, p, b0, 1.4, 0.45f)
                        note(Instrument.EPIANO, p, b0 + 2.5, 1.4, 0.38f)
                    }
                    val root = chord.rootMidiNear(38)
                    note(Instrument.SUB_BASS, root, b0, 1.5, 0.9f)
                    note(Instrument.SUB_BASS, root + 7, b0 + 2.5, 1.0, 0.7f)
                    hit(Drum.KICK, b0, 0.8f * drumVel); hit(Drum.KICK, b0 + 1.75, 0.5f * drumVel)
                    hit(Drum.KICK, b0 + 2.5, 0.7f * drumVel)
                    hit(Drum.SNARE, b0 + 1, 0.45f * drumVel); hit(Drum.SNARE, b0 + 3, 0.5f * drumVel)
                    for (i in 0 until 8) hit(Drum.HAT, b0 + i * 0.5, (0.25f + rng.nextFloat() * 0.15f) * drumVel)
                }
                Style.SYNTHWAVE -> {
                    for (p in chord.voicing(57)) note(Instrument.SAW_PAD, p, b0, bpb.toDouble(), 0.7f)
                    val root = chord.rootMidiNear(36)
                    for (i in 0 until bpb * 2) {
                        note(Instrument.SYNTH_BASS, if (i % 2 == 0) root else root + 12, b0 + i * 0.5, 0.42, 0.9f)
                    }
                    if (bar in sectionStarts && bar > 0) hit(Drum.CRASH, b0, 0.8f)
                    for (i in 0 until bpb) hit(Drum.KICK, b0 + i, 0.9f * drumVel)
                    hit(Drum.CLAP, b0 + 1, drumVel); hit(Drum.SNARE, b0 + 1, 0.6f * drumVel)
                    hit(Drum.CLAP, b0 + 3, drumVel); hit(Drum.SNARE, b0 + 3, 0.6f * drumVel)
                    for (i in 0 until 8) if (i % 2 == 1) hit(Drum.OPEN_HAT, b0 + i * 0.5, 0.35f * drumVel)
                }
                Style.JAZZ -> {
                    // Charleston-ish comping.
                    val v = chord.voicing(52)
                    val comp = if (rng.nextBoolean()) listOf(0.0 to 0.9, 1.5 to 0.5) else listOf(1.5 to 0.4, 3.0 to 0.8)
                    for ((on, d) in comp) for (p in v) note(Instrument.PIANO, p, b0 + on, d, 0.42f)
                    walkingBass(chord, song.chords.getOrNull(bar + 1) ?: chord, b0, rng, ::note)
                    for (i in listOf(0.0, 1.0, 1.5, 2.0, 3.0, 3.5)) {
                        hit(Drum.RIDE, b0 + i, (if (i % 1.0 == 0.0) 0.7f else 0.45f) * drumVel)
                    }
                    hit(Drum.HAT, b0 + 1, 0.45f * drumVel); hit(Drum.HAT, b0 + 3, 0.45f * drumVel)
                    if (rng.nextDouble() < 0.3) hit(Drum.SNARE, b0 + 2.5, 0.25f)
                }
                Style.MUSIC_BOX -> {
                    val v = chord.voicing(55)
                    val root = v[0]
                    val pattern = listOf(root - 12, v[2] - 12, v[1], v[2] - 12, root, v[2] - 12, v[1], v[2] - 12)
                    for ((i, p) in pattern.withIndex()) note(Instrument.MUSIC_BOX, p + 12, b0 + i * 0.5, 0.5, 0.33f)
                }
            }
        }

        val length = song.totalBeats * beatSec
        val fx = when (style) {
            Style.CHIPTUNE -> MasterFx(bitCrush = true)
            Style.ROCK -> MasterFx(delaySec = 0.09, delayFeedback = 0.2, delayMix = 0.12)
            Style.LOFI -> MasterFx(lowpassHz = 3800.0, crackle = true, delaySec = beatSec * 0.75, delayFeedback = 0.25, delayMix = 0.12)
            Style.SYNTHWAVE -> MasterFx(delaySec = beatSec * 0.75, delayFeedback = 0.35, delayMix = 0.25)
            Style.JAZZ -> MasterFx(delaySec = 0.07, delayFeedback = 0.3, delayMix = 0.12)
            Style.MUSIC_BOX -> MasterFx(delaySec = beatSec * 0.5, delayFeedback = 0.4, delayMix = 0.3)
        }
        return Arrangement(notes, drums, length, fx)
    }

    private fun walkingBass(
        chord: Chord, next: Chord, b0: Double, rng: Random,
        note: (Instrument, Int, Double, Double, Float) -> Unit,
    ) {
        val v = chord.voicing(36)
        val nextRoot = next.rootMidiNear(v[0])
        val approach = if (rng.nextBoolean()) nextRoot - 1 else nextRoot + 1
        val line = listOf(v[0], v[1], v[2], approach)
        for ((i, p) in line.withIndex()) note(Instrument.UPRIGHT_BASS, p, b0 + i, 0.95, 0.9f)
    }

    private fun finalBar(
        style: Style, chord: Chord, b0: Double,
        note: (Instrument, Int, Double, Double, Float) -> Unit,
        hit: (Drum, Double, Float) -> Unit,
    ) {
        val dur = 3.5
        when (style) {
            Style.CHIPTUNE -> {
                note(Instrument.TRIANGLE_8BIT, chord.rootMidiNear(40), b0, dur, 0.9f)
                for (p in chord.voicing(64)) note(Instrument.PULSE_12, p + 12, b0, dur, 0.3f)
                hit(Drum.CHIP_KICK, b0, 1f); hit(Drum.CHIP_SNARE, b0, 0.8f)
            }
            Style.ROCK -> {
                val root = chord.rootMidiNear(43)
                for (p in listOf(root, root + 7, root + 12)) note(Instrument.POWER_GUITAR, p, b0, dur, 0.9f)
                note(Instrument.PICK_BASS, root - 12, b0, dur, 1f)
                hit(Drum.KICK, b0, 1f); hit(Drum.CRASH, b0, 1f)
            }
            Style.LOFI -> {
                for (p in chord.voicing(55)) note(Instrument.EPIANO, p, b0, dur, 0.45f)
                note(Instrument.SUB_BASS, chord.rootMidiNear(38), b0, dur, 0.9f)
                hit(Drum.KICK, b0, 0.8f)
            }
            Style.SYNTHWAVE -> {
                for (p in chord.voicing(57)) note(Instrument.SAW_PAD, p, b0, dur, 0.8f)
                note(Instrument.SYNTH_BASS, chord.rootMidiNear(36), b0, dur, 0.9f)
                hit(Drum.KICK, b0, 1f); hit(Drum.CRASH, b0, 0.9f)
            }
            Style.JAZZ -> {
                for (p in chord.voicing(52)) note(Instrument.PIANO, p, b0, dur, 0.45f)
                note(Instrument.UPRIGHT_BASS, chord.rootMidiNear(36), b0, dur, 0.9f)
                hit(Drum.RIDE, b0, 0.7f); hit(Drum.KICK, b0, 0.6f)
            }
            Style.MUSIC_BOX -> {
                val v = chord.voicing(55)
                for ((i, p) in v.withIndex()) note(Instrument.MUSIC_BOX, p + 12, b0 + i * 0.12, dur, 0.35f)
            }
        }
    }
}
