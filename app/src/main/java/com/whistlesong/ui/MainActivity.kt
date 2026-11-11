package com.whistlesong.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whistlesong.music.Style
import com.whistlesong.music.noteName
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val Bg = Color(0xFF12101C)
val Panel = Color(0xFF1D1A2B)
val UserColor = Color(0xFF4DD0E1)
val AiColor = Color(0xFFFF4FA3)
val Accent = Color(0xFF8C6CFF)
val RecordRed = Color(0xFFFF4D5E)

class MainActivity : ComponentActivity() {
    private val vm: WhistleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, secondary = AiColor, background = Bg, surface = Panel,
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    WhistleScreen(vm)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.stopPlayback()
    }
}

@Composable
fun WhistleScreen(vm: WhistleViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording()
    }

    fun onRecordTapped() {
        when (s.phase) {
            Phase.RECORDING -> vm.stopRecording()
            Phase.PROCESSING -> Unit
            else -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) vm.startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text("WhistleSong", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text(
                "Whistle a few notes. I'll tune them and write the rest.",
                fontSize = 14.sp, color = Color.White.copy(alpha = 0.65f),
            )
        }

        // ---------------- Visual panel
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Panel),
        ) {
            when (s.phase) {
                Phase.IDLE -> Hint()
                Phase.RECORDING -> LiveView(s)
                Phase.PROCESSING -> Busy("Tuning your whistle and composing…")
                Phase.READY -> s.song?.let { PianoRoll(it, s.whistleCurve, s.playheadBeat, Modifier.fillMaxSize()) }
            }
            if (s.rendering && s.phase == Phase.READY) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp),
                    strokeWidth = 2.dp, color = Color.White,
                )
            }
        }

        s.message?.let {
            Text(
                it, color = Color(0xFFFFC66D), fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x33FFC66D))
                    .clickable { vm.dismissMessage() }
                    .padding(12.dp),
            )
        }

        // ---------------- Song info + legend
        s.song?.let { song ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoPill("🎼 ${song.key.name}")
                InfoPill("⏱ ${song.bpm.roundToInt()} BPM")
                InfoPill("${song.totalBars} bars")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(UserColor, "You (autotuned)")
                Legend(AiColor, "AI continuation")
            }
        }

        // ---------------- Record button
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RecordButton(s.phase) { onRecordTapped() }
        }

        // ---------------- Style picker
        Text("Style", color = Color.White, fontWeight = FontWeight.Bold)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (style in Style.entries) StyleChip(style, style == s.style) { vm.setStyle(style) }
        }
        Text(s.style.description, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)

        // ---------------- Length
        Text("How much should I write?", color = Color.White, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (bars in listOf(4, 8, 16)) {
                SegmentChip("+$bars bars", s.continuationBars == bars) { vm.setContinuation(bars) }
            }
        }

        // ---------------- Actions
        if (s.song != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val playingSong = s.playing == Playing.SONG
                ActionButton(if (playingSong) "■  Stop" else "▶  Play song", Modifier.weight(1f), enabled = !s.rendering) {
                    if (playingSong) vm.stopPlayback() else vm.playSong()
                }
                ActionButton("🎲  New idea", Modifier.weight(1f), enabled = !s.rendering) { vm.newIdea() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val playingOrig = s.playing == Playing.ORIGINAL
                ActionButton(if (playingOrig) "■  Stop" else "🎤  My whistle", Modifier.weight(1f)) {
                    if (playingOrig) vm.stopPlayback() else vm.playOriginal()
                }
                ActionButton("⤴  Share WAV", Modifier.weight(1f), enabled = !s.rendering) {
                    scope.launch {
                        val f = vm.exportWav() ?: return@launch
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/wav"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Share your song"))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Hint() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🎶", fontSize = 48.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap the button and whistle 2–8 seconds of a melody.\nTap again when you're done.",
            color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Busy(text: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = AiColor)
        Spacer(Modifier.height(12.dp))
        Text(text, color = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
private fun LiveView(s: UiState) {
    Box(Modifier.fillMaxSize()) {
        LivePitchTrail(s.liveTrail, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).padding(14.dp)) {
            Text(
                s.liveMidi?.let { noteName(it.roundToInt()) } ?: "…",
                fontSize = 40.sp, fontWeight = FontWeight.Black, color = UserColor,
            )
            Text("%.1fs".format(s.recordingSec), color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun RecordButton(phase: Phase, onClick: () -> Unit) {
    val recording = phase == Phase.RECORDING
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        1f, if (recording) 1.08f else 1f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "scale",
    )
    Box(
        Modifier
            .size(96.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (recording) RecordRed else Accent)
            .border(4.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(enabled = phase != Phase.PROCESSING, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color.White))
        } else {
            Text(if (phase == Phase.READY) "🎤\nagain" else "🎤", textAlign = TextAlign.Center, fontSize = if (phase == Phase.READY) 18.sp else 36.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun StyleChip(style: Style, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Accent else Panel)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(style.emoji, fontSize = 26.sp)
        Text(style.label, color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else Panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun InfoPill(text: String) {
    Text(
        text, color = Color.White, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Panel).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(52.dp), enabled = enabled) {
        Text(label, fontSize = 15.sp)
    }
}
