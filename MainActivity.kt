package com.cleanboost

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

private val Accent = Color(0xFF4FD1C5)
private val Warn = Color(0xFFFFB74D)
private val Bg = Color(0xFF0F1214)
private val Card = Color(0xFF181D20)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = SettingsStore.load(this)
        if (s.enabled) BoostService.start(this, sessionFrom(intent))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sessionFrom(intent).takeIf { it > 0 }?.let { BoostService.start(this, it) }
    }

    /** A music player opened us from its EQ button and told us its session. */
    private fun sessionFrom(i: Intent?): Int =
        i?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1) ?: -1
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(SettingsStore.load(ctx)) }
    fun update(n: BoostSettings) {
        val wasOn = s.enabled
        s = n
        SettingsStore.save(ctx, n)
        if (n.enabled && !wasOn) BoostService.start(ctx)
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Android gates the output level meter (Visualizer API) behind RECORD_AUDIO.
    // Nothing is recorded; it's used only to measure peaks for Clip Guard.
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        // Re-save to make the service re-create the meter now that it's permitted.
        update(s.copy(clipGuard = ok))
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(Unit) {
        if (s.clipGuard && !hasMic(ctx)) micPerm.launch(Manifest.permission.RECORD_AUDIO)
        else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------------------------------------------------------- header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CleanBoost", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    if (s.enabled) "Running · peaks held at ${"%.1f".format(s.ceilingDb)} dBFS"
                    else "Off", color = if (s.enabled) Accent else Color.Gray, fontSize = 13.sp
                )
            }
            Switch(checked = s.enabled, onCheckedChange = { update(s.copy(enabled = it)) })
        }

        // ---------------------------------------------------------- boost
        Section("Volume boost") {
            Text("+%.1f dB".format(s.boostDb), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Accent)
            Slider(
                value = s.boostDb, valueRange = 0f..s.maxBoostDb,
                onValueChange = { update(s.copy(boostDb = it)) }
            )
            val heavy = s.boostDb > 18f
            Text(
                if (heavy) "Still clip-free, but this far up the limiter is doing most of the work: " +
                        "more boost now means more density, not more peak level. Density/Exciter sound cleaner here."
                else "Boost goes in before the limiter; Clip Guard keeps output peaks under the ceiling.",
                fontSize = 12.sp, color = if (heavy) Warn else Color.Gray
            )
            ClipGuardStatus(s)
        }

        // ---------------------------------------------------------- dynamics
        Section("Dynamics") {
            LabeledSlider("Density (multiband compression)", s.density, 0f..1f,
                "%.0f%%".format(s.density * 100)) { update(s.copy(density = it)) }
            LabeledSlider("Transient preservation (attack)", s.attackMs, 3f..80f,
                "%.0f ms".format(s.attackMs)) { update(s.copy(attackMs = it)) }
            Text("Longer attack lets the hit of each drum/pluck through before the compressor reacts.",
                fontSize = 12.sp, color = Color.Gray)
            LabeledSlider("Limiter ceiling", s.ceilingDb, -6f..-0.1f,
                "%.1f dBFS".format(s.ceilingDb)) { update(s.copy(ceilingDb = it)) }
        }

        // ---------------------------------------------------------- enhance
        Section("Enhance") {
            LabeledSlider("Exciter / air (lifts quiet highs)", s.exciterDb, 0f..9f,
                "+%.1f dB".format(s.exciterDb)) { update(s.copy(exciterDb = it)) }
            LabeledSlider("Warmth (lifts quiet lows)", s.warmthDb, 0f..9f,
                "+%.1f dB".format(s.warmthDb)) { update(s.copy(warmthDb = it)) }
        }

        // ---------------------------------------------------------- parametric EQ
        Section("Parametric EQ") {
            EqCurve(s.bands)
            s.bands.forEachIndexed { idx, b ->
                BandEditor(idx, b,
                    onChange = { nb -> update(s.copy(bands = s.bands.toMutableList().also { it[idx] = nb })) },
                    onDelete = { update(s.copy(bands = s.bands.toMutableList().also { it.removeAt(idx) })) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { update(s.copy(bands = s.bands + PeqBand())) },
                    enabled = s.bands.size < 12) { Text("Add band") }
                OutlinedButton(onClick = { update(s.copy(bands = BoostSettings.DEFAULT_BANDS)) }) { Text("Reset EQ") }
            }
        }

        // ---------------------------------------------------------- advanced
        Section("Keep running & advanced") {
            val ignoring = BoostService.isIgnoringBatteryOptimizations(ctx)
            Text(
                if (ignoring) "✓ Exempt from battery optimisation" else "Battery optimisation can kill the engine.",
                color = if (ignoring) Accent else Warn, fontSize = 13.sp
            )
            if (!ignoring) Button(onClick = { requestBatteryExemption(ctx) }) { Text("Allow background running") }
            ToggleRow("Global mode (all apps)", s.globalMode) { update(s.copy(globalMode = it)) }
            ToggleRow("Clip Guard (live crest tracking)", s.clipGuard) { on ->
                if (on && !hasMic(ctx)) micPerm.launch(Manifest.permission.RECORD_AUDIO)
                else update(s.copy(clipGuard = on))
            }
            if (!s.clipGuard) Text("Off: limiter uses a fixed 18 dB safety margin. Still clip-free, but quieter.",
                fontSize = 12.sp, color = Color.Gray)
            LabeledSlider("Boost slider range", s.maxBoostDb, 12f..48f, "%.0f dB".format(s.maxBoostDb)) {
                update(s.copy(maxBoostDb = it, boostDb = s.boostDb.coerceAtMost(it)))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@SuppressLint("BatteryLife")
private fun requestBatteryExemption(ctx: android.content.Context) {
    runCatching {
        ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}")))
    }
}

// ================================================================== components

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title.uppercase(), fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    display: String, onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(display, color = Accent, fontSize = 14.sp)
        }
        Slider(value = value.coerceIn(range), valueRange = range, onValueChange = onChange)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun hasMic(ctx: android.content.Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ClipGuardStatus(s: BoostSettings) {
    var peak by remember { mutableStateOf<Float?>(null) }
    var maxPeak by remember { mutableStateOf(-96f) }
    var margin by remember { mutableStateOf(18f) }
    var live by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val e = BoostService.instance?.engine
            peak = e?.lastPeakDb; maxPeak = e?.maxPeakDb ?: -96f
            margin = e?.crestMarginDb ?: 18f; live = e?.meterAvailable == true
            delay(100)
        }
    }
    if (!s.enabled) return
    val p = peak
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val frac = if (p == null) 0f else ((p + 48f) / 48f).coerceIn(0f, 1f)
        val ceilFrac = ((s.ceilingDb + 48f) / 48f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(10.dp).background(Color(0xFF2A3135), RoundedCornerShape(5.dp))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight()
                .background(if ((p ?: -96f) > s.ceilingDb) Warn else Accent, RoundedCornerShape(5.dp)))
            Box(Modifier.fillMaxWidth(ceilFrac).fillMaxHeight()) {
                Box(Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Color.White))
            }
        }
        Text(
            if (live && p != null)
                "Output peak %.1f dBFS · highest %.1f · crest margin %.1f dB".format(p, maxPeak, margin)
            else "Clip Guard meter off — using fixed %.0f dB safety margin".format(margin),
            fontSize = 12.sp, color = if (maxPeak >= -0.01f) Color.Red else Color.Gray
        )
    }
}

@Composable
private fun EqCurve(bands: List<PeqBand>) {
    Canvas(Modifier.fillMaxWidth().height(140.dp).background(Color(0xFF111517), RoundedCornerShape(8.dp))) {
        val range = 18f
        fun yFor(db: Float) = size.height / 2 - (db / range) * (size.height / 2)
        listOf(-12f, -6f, 0f, 6f, 12f).forEach { db ->
            drawLine(Color(0x22FFFFFF), Offset(0f, yFor(db)), Offset(size.width, yFor(db)))
        }
        listOf(100f, 1000f, 10000f).forEach { f ->
            val x = (log10(f / 20f) / 3f) * size.width
            drawLine(Color(0x22FFFFFF), Offset(x, 0f), Offset(x, size.height))
        }
        val path = Path()
        val n = 200
        for (i in 0..n) {
            val f = 20.0 * 1000.0.pow(i / n.toDouble())
            val db = ParametricEq.responseDb(bands, f).toFloat().coerceIn(-range, range)
            val x = i / n.toFloat() * size.width
            if (i == 0) path.moveTo(x, yFor(db)) else path.lineTo(x, yFor(db))
        }
        drawPath(path, Accent, style = Stroke(width = 3f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandEditor(idx: Int, b: PeqBand, onChange: (PeqBand) -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Color(0xFF1F2528), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = b.enabled, onCheckedChange = { onChange(b.copy(enabled = it)) })
            Box {
                TextButton(onClick = { expanded = true }) { Text("${idx + 1}. ${b.type.label} ▾") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    FilterType.entries.forEach { t ->
                        DropdownMenuItem(text = { Text(t.label) }, onClick = { onChange(b.copy(type = t)); expanded = false })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("Remove", color = Color.Gray) }
        }
        // Log-scale frequency slider: 0..1 ↔ 20 Hz..20 kHz
        val pos = (ln(b.freq / 20f) / ln(1000f)).coerceIn(0f, 1f)
        LabeledSlider("Frequency", pos, 0f..1f, fmtHz(b.freq)) {
            onChange(b.copy(freq = 20f * 1000f.pow(it)))
        }
        if (b.type != FilterType.HIGH_PASS && b.type != FilterType.LOW_PASS) {
            LabeledSlider("Gain", b.gain, -15f..15f, "%+.1f dB".format(b.gain)) { onChange(b.copy(gain = it)) }
        }
        LabeledSlider("Q", b.q, 0.3f..10f, "%.2f".format(b.q)) { onChange(b.copy(q = it)) }
    }
}

private fun fmtHz(f: Float) = if (f >= 1000f) "%.2f kHz".format(f / 1000f) else "%.0f Hz".format(f)
