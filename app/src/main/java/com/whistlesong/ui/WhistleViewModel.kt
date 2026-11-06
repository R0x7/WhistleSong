package com.whistlesong.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whistlesong.audio.Player
import com.whistlesong.audio.WhistleRecorder
import com.whistlesong.dsp.PitchFrame
import com.whistlesong.dsp.YinPitchDetector
import com.whistlesong.music.Composer
import com.whistlesong.music.Song
import com.whistlesong.music.Style
import com.whistlesong.music.Transcriber
import com.whistlesong.music.Transcription
import com.whistlesong.synth.Arranger
import com.whistlesong.synth.Synth
import com.whistlesong.synth.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Phase { IDLE, RECORDING, PROCESSING, READY }
enum class Playing { NONE, SONG, ORIGINAL }

/** A point of the raw whistle curve, in song beats / MIDI (already octave-shifted like the melody). */
data class CurvePoint(val beat: Double, val midi: Double)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val liveTrail: List<Double?> = emptyList(),
    val liveMidi: Double? = null,
    val recordingSec: Double = 0.0,
    val style: Style = Style.CHIPTUNE,
    val continuationBars: Int = 8,
    val seed: Long = 1L,
    val transcription: Transcription? = null,
    val song: Song? = null,
    val whistleCurve: List<CurvePoint> = emptyList(),
    val rendering: Boolean = false,
    val playing: Playing = Playing.NONE,
    val playheadBeat: Double? = null,
    val message: String? = null,
)

class WhistleViewModel(app: Application) : AndroidViewModel(app) {
    private val sampleRate = 44100
    private val recorder = WhistleRecorder(sampleRate)
    private val player = Player(sampleRate)
    private val synth = Synth(sampleRate)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var recording: FloatArray? = null
    private var pitchFrames: List<PitchFrame> = emptyList()
    private var songPcm: ShortArray? = null
    private var composeJob: Job? = null

    // ------------------------------------------------------------------ recording

    fun startRecording() {
        stopPlayback()
        composeJob?.cancel()
        _state.update {
            it.copy(phase = Phase.RECORDING, liveTrail = emptyList(), liveMidi = null, recordingSec = 0.0, message = null)
        }
        recorder.start(
            viewModelScope,
            onFrame = { f ->
                val midi = if (f.hz != null && f.confidence > 0.6 && f.rms > 0.01) f.midi else null
                _state.update { s ->
                    s.copy(
                        liveTrail = (s.liveTrail + midi).takeLast(120),
                        liveMidi = midi,
                        recordingSec = f.timeSec,
                    )
                }
            },
            onDone = { samples -> process(samples) },
        )
    }

    fun stopRecording() {
        recorder.stop()
        _state.update { it.copy(phase = Phase.PROCESSING) }
    }

    private fun process(samples: FloatArray) {
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(phase = Phase.PROCESSING) }
            recording = samples
            val frames = YinPitchDetector(sampleRate).track(samples, hop = sampleRate / 100)
            pitchFrames = frames
            val t = Transcriber.transcribe(frames)
            if (t == null || t.notes.size < 2) {
                _state.update {
                    it.copy(
                        phase = if (it.song != null) Phase.READY else Phase.IDLE,
                        message = "I couldn't hear a clear whistle. Try again a little closer to the mic, with at least a few notes.",
                    )
                }
                return@launch
            }
            _state.update { it.copy(transcription = t, seed = System.currentTimeMillis()) }
            recompose()
        }
    }

    // ------------------------------------------------------------------ composing

    fun setStyle(style: Style) {
        if (style == _state.value.style) return
        _state.update { it.copy(style = style) }
        recompose()
    }

    fun setContinuation(bars: Int) {
        if (bars == _state.value.continuationBars) return
        _state.update { it.copy(continuationBars = bars) }
        recompose()
    }

    fun newIdea() {
        _state.update { it.copy(seed = it.seed * 6364136223846793005L + 1442695040888963407L) }
        recompose()
    }

    private fun recompose() {
        val s = _state.value
        val t = s.transcription ?: return
        val wasPlaying = s.playing == Playing.SONG
        stopPlayback()
        composeJob?.cancel()
        composeJob = viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(rendering = true) }
            val song = Composer(s.style, s.seed).compose(t, s.continuationBars)
            val curve = whistleCurve(t, song)
            _state.update { it.copy(song = song, whistleCurve = curve, phase = Phase.READY) }
            val audio = synth.render(Arranger.arrange(song, s.seed))
            val pcm = Synth.toPcm16(audio)
            songPcm = pcm
            _state.update { it.copy(rendering = false) }
            if (wasPlaying || s.phase == Phase.PROCESSING) withContext(Dispatchers.Main) { playSong() }
        }
    }

    /** Maps the raw pitch track onto the song's beat grid so the UI can draw it under the notes. */
    private fun whistleCurve(t: Transcription, song: Song): List<CurvePoint> {
        val firstUser = song.melody.firstOrNull { it.fromUser } ?: return emptyList()
        val octaveShift = firstUser.pitch - t.notes.first().pitch
        val t0 = t.rawNotes.first().startSec
        val beatSec = 60.0 / t.bpm
        val tune = t.tuningCents / 100.0
        return pitchFrames.mapNotNull { f ->
            val m = f.midi ?: return@mapNotNull null
            if (f.confidence < 0.6 || f.timeSec < t0 - 0.05 || f.timeSec > t.rawNotes.last().endSec + 0.05) return@mapNotNull null
            CurvePoint((f.timeSec - t0) / beatSec, m - tune + octaveShift)
        }
    }

    // ------------------------------------------------------------------ playback

    fun playSong() {
        val pcm = songPcm ?: return
        val song = _state.value.song ?: return
        player.play(
            viewModelScope, pcm,
            onPosition = { sec -> _state.update { it.copy(playheadBeat = sec / song.beatSec) } },
            onFinished = { _state.update { it.copy(playing = Playing.NONE, playheadBeat = null) } },
        )
        _state.update { it.copy(playing = Playing.SONG) }
    }

    fun playOriginal() {
        val rec = recording ?: return
        player.play(
            viewModelScope, Synth.toPcm16(rec),
            onPosition = { },
            onFinished = { _state.update { it.copy(playing = Playing.NONE) } },
        )
        _state.update { it.copy(playing = Playing.ORIGINAL, playheadBeat = null) }
    }

    fun stopPlayback() {
        player.stop()
        _state.update { it.copy(playing = Playing.NONE, playheadBeat = null) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Writes the current song to a WAV in the cache dir (for sharing). */
    suspend fun exportWav(): File? = withContext(Dispatchers.IO) {
        val pcm = songPcm ?: return@withContext null
        val style = _state.value.style
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "whistlesong-${style.name.lowercase()}.wav")
        WavWriter.write(f, pcm, sampleRate)
        f
    }

    override fun onCleared() {
        recorder.stop()
        player.stop()
    }
}
