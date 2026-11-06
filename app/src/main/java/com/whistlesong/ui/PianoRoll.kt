package com.whistlesong.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whistlesong.music.SectionKind
import com.whistlesong.music.Song
import kotlin.math.max
import kotlin.math.min

private val BLACK_KEYS = setOf(1, 3, 6, 8, 10)

@Composable
fun PianoRoll(song: Song, curve: List<CurvePoint>, playhead: Double?, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val header = 40.dp.toPx()
        val pad = 8.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - header - pad
        val beats = song.totalBeats
        val xOf = { b: Double -> pad + (b / beats * w).toFloat() }

        val pitches = song.melody.map { it.pitch.toDouble() } + curve.map { it.midi }
        val lo = (pitches.minOrNull() ?: 60.0).toInt() - 2
        val hi = (pitches.maxOrNull() ?: 72.0).toInt() + 2
        val rows = max(12, hi - lo + 1)
        val rowH = h / rows
        val yOf = { m: Double -> header + ((hi - m) / rows * h).toFloat() }

        // Key rows.
        for (p in lo..hi) {
            if (Math.floorMod(p, 12) in BLACK_KEYS) {
                drawRect(Color.White.copy(alpha = 0.03f), Offset(pad, yOf(p + 0.5)), Size(w, rowH))
            }
        }
        // Sections band + chord names.
        val barW = w / song.totalBars
        for (sec in song.sections) {
            val x0 = xOf(sec.startBar * song.beatsPerBar.toDouble())
            val x1 = xOf((sec.startBar + sec.bars) * song.beatsPerBar.toDouble())
            val c = if (sec.kind == SectionKind.YOURS) UserColor else AiColor
            drawRoundRect(c.copy(alpha = 0.22f), Offset(x0 + 1, 4.dp.toPx()), Size(x1 - x0 - 2, 16.dp.toPx()), CornerRadius(6f))
            val label = measurer.measure(sec.kind.label, TextStyle(color = Color.White, fontSize = 10.sp))
            if (label.size.width < x1 - x0 - 6) {
                drawText(label, topLeft = Offset(x0 + 4.dp.toPx(), 5.dp.toPx()))
            }
        }
        for (bar in 0 until song.totalBars) {
            val x = xOf(bar * song.beatsPerBar.toDouble())
            drawLine(Color.White.copy(alpha = 0.12f), Offset(x, header), Offset(x, header + h), 1f)
            if (barW > 26.dp.toPx()) {
                val t = measurer.measure(song.chords[bar].symbol, TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp))
                drawText(t, topLeft = Offset(x + 3.dp.toPx(), 23.dp.toPx()))
            }
        }

        // Raw whistle curve.
        if (curve.isNotEmpty()) {
            val path = Path()
            var prev: CurvePoint? = null
            for (p in curve) {
                val x = xOf(p.beat); val y = yOf(p.midi)
                if (prev == null || p.beat - prev.beat > 0.2) path.moveTo(x, y) else path.lineTo(x, y)
                prev = p
            }
            drawPath(path, Color.White.copy(alpha = 0.35f), style = Stroke(width = 1.5.dp.toPx()))
        }

        // Notes.
        for (n in song.melody) {
            val x0 = xOf(n.start)
            val x1 = xOf(n.end)
            val y = yOf(n.pitch + 0.5)
            val active = playhead != null && playhead >= n.start && playhead < n.end
            val c = if (n.fromUser) UserColor else AiColor
            drawRoundRect(
                if (active) Color.White else c,
                Offset(x0, y + 1), Size(max(2f, x1 - x0 - 1.5f), max(2f, min(rowH - 2, 14.dp.toPx()))),
                CornerRadius(3.dp.toPx()),
            )
        }

        // Playhead.
        if (playhead != null) {
            val x = xOf(playhead.coerceIn(0.0, beats))
            drawLine(Color.White, Offset(x, header - 4), Offset(x, header + h), 2.dp.toPx())
        }
    }
}

@Composable
fun LivePitchTrail(trail: List<Double?>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val voiced = trail.filterNotNull()
        if (voiced.isEmpty()) return@Canvas
        val lo = voiced.min() - 3
        val hi = voiced.max() + 3
        val range = max(12.0, hi - lo)
        val mid = (hi + lo) / 2
        val stepX = size.width / 120f
        val yOf = { m: Double -> (size.height * (0.5 - (m - mid) / range)).toFloat() }
        // Semitone grid.
        for (p in (mid - range / 2).toInt()..(mid + range / 2).toInt()) {
            val y = yOf(p.toDouble())
            drawLine(Color.White.copy(alpha = if (Math.floorMod(p, 12) == 0) 0.15f else 0.05f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        val offset = 120 - trail.size
        for (i in trail.indices) {
            val m = trail[i] ?: continue
            val x = (offset + i) * stepX
            drawCircle(UserColor.copy(alpha = 0.3f + 0.7f * i / trail.size), 3.dp.toPx(), Offset(x, yOf(m)))
        }
    }
}
