package com.whistlesong.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Streams a PCM buffer to AudioTrack and reports playback position. */
class Player(private val sampleRate: Int) {
    private var track: AudioTrack? = null
    private var writer: Job? = null
    private var poller: Job? = null

    fun play(
        scope: CoroutineScope,
        pcm: ShortArray,
        onPosition: (Double) -> Unit,
        onFinished: () -> Unit,
    ) {
        stop()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        writer = scope.launch(Dispatchers.IO) {
            var off = 0
            val chunk = 4096
            while (isActive && off < pcm.size) {
                val n = t.write(pcm, off, minOf(chunk, pcm.size - off))
                if (n < 0) break
                off += n
            }
        }
        poller = scope.launch {
            var last = -1
            var stalledSince = 0L
            while (isActive) {
                val pos = runCatching { t.playbackHeadPosition }.getOrDefault(0)
                onPosition(pos.toDouble() / sampleRate)
                if (pos >= pcm.size - 1) break
                // Guard against devices whose head never reaches the very last frame.
                if (pos == last && writer?.isActive != true) {
                    if (stalledSince == 0L) stalledSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - stalledSince > 400) break
                } else stalledSince = 0L
                last = pos
                delay(30)
            }
            poller = null
            release()
            onFinished()
        }
    }

    fun stop() {
        poller?.cancel(); poller = null
        release()
    }

    private fun release() {
        writer?.cancel(); writer = null
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
    }
}
