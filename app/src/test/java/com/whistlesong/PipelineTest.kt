package com.whistlesong

import com.whistlesong.dsp.YinPitchDetector
import com.whistlesong.dsp.midiToHz
import com.whistlesong.music.Composer
import com.whistlesong.music.Style
import com.whistlesong.music.Transcriber
import com.whistlesong.synth.Arranger
import com.whistlesong.synth.Synth
import com.whistlesong.synth.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class PipelineTest {
    private val sr = 44100

    /**
     * Synthesises a slightly out-of-tune, vibrato-y, breathy "whistle" of a melody given as
     * (midi, beats) at a tempo, with gaps between notes.
     */
    private fun fakeWhistle(melody: List<Pair<Int, Double>>, bpm: Double, detuneCents: Double = 30.0): FloatArray {
        val beat = 60.0 / bpm
        val total = melody.sumOf { it.second } * beat + 1.0
        val out = FloatArray((total * sr).toInt())
        val rnd = Random(3)
        var t0 = 0.3
        var phase = 0.0
        for ((m, b) in melody) {
            val dur = b * beat
            val len = ((dur - 0.04) * sr).toInt()
            val start = (t0 * sr).toInt()
            for (i in 0 until len) {
                val t = i.toDouble() / sr
                val vib = 0.15 * sin(2 * PI * 5.5 * t) // ±15 cents
                val f = midiToHz(m + detuneCents / 100 + vib + 0.02 * rnd.nextDouble(-1.0, 1.0))
                phase += f / sr
                val env = minOf(1.0, t / 0.02, (len - i).toDouble() / sr / 0.02)
                out[start + i] = (0.3 * env * sin(2 * PI * phase) + 0.01 * rnd.nextDouble(-1.0, 1.0)).toFloat()
            }
            t0 += dur
        }
        return out
    }

    // "Ode to Joy"-ish opening in G major, whistled high.
    private val melody = listOf(
        83 to 1.0, 83 to 1.0, 84 to 1.0, 86 to 1.0,
        86 to 1.0, 84 to 1.0, 83 to 1.0, 81 to 1.0,
        79 to 1.0, 79 to 1.0, 81 to 1.0, 83 to 1.0,
        83 to 1.5, 81 to 0.5, 81 to 2.0,
    )

    @Test
    fun detectsPitch() {
        val det = YinPitchDetector(sr)
        val buf = FloatArray(4096) { sin(2 * PI * 1500.0 * it / sr).toFloat() * 0.3f }
        val f = det.detect(buf, 0)
        assertTrue("got ${f.hz}", abs(f.hz!! - 1500.0) < 3.0)
    }

    @Test
    fun transcribesAndAutotunes() {
        val audio = fakeWhistle(melody, 100.0)
        val frames = YinPitchDetector(sr).track(audio)
        val t = Transcriber.transcribe(frames)!!
        println("key=${t.key.name} bpm=${t.bpm} tuning=${t.tuningCents}c")
        println(t.notes.joinToString { "${it.pitch}@${it.start}/${it.duration}" })
        assertEquals(melody.size, t.notes.size)
        assertEquals(melody.map { it.first }, t.notes.map { it.pitch })
        // The phrase only uses G A B C D, so G major / E minor / C major / A minor are all valid.
        assertTrue(t.key.name, melody.all { t.key.inScale(it.first) })
        assertTrue("bpm ${t.bpm}", abs(t.bpm - 100) < 4 || abs(t.bpm - 200) < 8 || abs(t.bpm - 50) < 3)
        // Starts land on the grid.
        val expectedStarts = melody.runningFold(0.0) { a, n -> a + n.second }.dropLast(1)
        val scale = t.notes[1].start / expectedStarts[1]
        for (i in melody.indices) assertEquals(expectedStarts[i] * scale, t.notes[i].start, 0.01)
    }

    @Test
    fun composesEveryStyle() {
        val frames = YinPitchDetector(sr).track(fakeWhistle(melody, 100.0))
        val t = Transcriber.transcribe(frames)!!
        val outDir = File("build/test-songs").apply { mkdirs() }
        for (style in Style.entries) {
            for (seed in 1L..3L) {
                val song = Composer(style, seed).compose(t, 8)
                assertTrue(song.totalBars >= song.userBars + 8)
                assertTrue(song.melody.any { !it.fromUser })
                // Every generated note is in key (except jazz chromatic approach tones).
                if (style != Style.JAZZ) {
                    assertTrue(song.melody.all { song.key.inScale(it.pitch) })
                }
                // Ends on the tonic.
                val last = song.melody.maxBy { it.start }
                assertEquals(song.key.tonicPc, Math.floorMod(last.pitch, 12))
                // No overlapping melody notes.
                val sorted = song.melody.sortedBy { it.start }
                for (i in 0 until sorted.size - 1) assertTrue(sorted[i].end <= sorted[i + 1].start + 1e-6)

                if (seed == 1L) {
                    val t0 = System.nanoTime()
                    val audio = Synth(sr).render(Arranger.arrange(song))
                    val ms = (System.nanoTime() - t0) / 1e6
                    assertTrue(audio.all { !it.isNaN() && abs(it) <= 1f })
                    WavWriter.write(File(outDir, "${style.name.lowercase()}.wav"), Synth.toPcm16(audio), sr)
                    println("${style.label}: ${song.key.name} ${song.bpm.toInt()}bpm ${song.totalBars} bars, " +
                        "chords=${song.chords.joinToString(" ") { it.symbol }}, rendered in ${ms.toInt()}ms")
                }
            }
        }
    }
}
