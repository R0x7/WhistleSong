package com.whistlesong.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.whistlesong.dsp.PitchFrame
import com.whistlesong.dsp.YinPitchDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Records from the microphone and reports live pitch while recording.
 * Recording stops when [stop] is called or after [maxSeconds].
 */
class WhistleRecorder(val sampleRate: Int = 44100, private val maxSeconds: Int = 20) {

    private var job: Job? = null
    @Volatile private var stopRequested = false

    /**
     * @param onFrame called (on a background thread) with each live pitch frame.
     * @param onDone called (on a background thread) with all recorded samples.
     */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, onFrame: (PitchFrame) -> Unit, onDone: (FloatArray) -> Unit) {
        stopRequested = false
        job = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, 8192),
            )
            val detector = YinPitchDetector(sampleRate)
            val maxSamples = sampleRate * maxSeconds
            val all = FloatArray(maxSamples)
            var count = 0
            val chunk = ShortArray(1024)
            var nextAnalysis = 0
            val hop = sampleRate / 25 // live display at 25 fps is plenty

            try {
                record.startRecording()
                while (isActive && !stopRequested && count < maxSamples) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    val m = minOf(n, maxSamples - count)
                    for (i in 0 until m) all[count + i] = chunk[i] / 32768f
                    count += m
                    while (nextAnalysis + detector.frameSize <= count) {
                        onFrame(detector.detect(all, nextAnalysis, nextAnalysis.toDouble() / sampleRate))
                        nextAnalysis += hop
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
            onDone(all.copyOf(count))
        }
    }

    fun stop() {
        stopRequested = true
    }
}
