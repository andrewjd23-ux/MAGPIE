package uk.co.magpie.instrument.vision

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import uk.co.magpie.instrument.InstrumentModel
import java.util.Locale
import kotlin.math.min

@Composable
fun TargetPanel(target: AcquiredTarget, targets: TargetAcquisitionModel) {
    val amber = MaterialTheme.colorScheme.secondary
    Text("ACQUIRED TARGET • ${target.detection.label} • object score ${String.format(Locale.UK, "%.2f", target.detection.score)} (uncalibrated)", color = amber)
    Text("Saved acquisition frame • amber target • other boxes dimmed • not tracking", style = MaterialTheme.typography.bodySmall)
    TargetSnapshot(target, Modifier.fillMaxWidth().height(150.dp))
    target.crop?.let { crop ->
        Text("Acquired image crop", style = MaterialTheme.typography.bodySmall)
        Image(crop.asImageBitmap(), "Selected object crop", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(100.dp).border(2.dp, amber))
    }
    TextButton(onClick = targets::release) { Text("Release target") }
    when (val material = target.material) {
        MaterialResult.Pending -> Text("Analyzing selected crop…")
        is MaterialResult.Unavailable -> Text(material.reason, color = amber)
        is MaterialResult.Predictions -> {
            Text("Surface-material candidates • uncalibrated scores", color = amber)
            Text(material.model, style = MaterialTheme.typography.bodySmall)
            Text("Experimental visible-surface suggestions. Background, coatings and mixed materials can affect results; composition and alloy identity are not established.", style = MaterialTheme.typography.bodySmall)
            material.scores.sortedByDescending { it.score }.take(5).forEach {
                Text("${it.label}: ${String.format(Locale.UK, "%.3f", it.score)}")
            }
        }
    }
    MaterialModelInfo()
    Text(explainTarget(target.decision, target.material, targets.scan.result), color = amber)
}

@Composable
fun TargetSnapshot(target: AcquiredTarget, modifier: Modifier) {
    Canvas(modifier.border(2.dp, MaterialTheme.colorScheme.secondary)) {
        val frame = target.frame
        val scale = min(size.width / frame.viewWidth, size.height / frame.viewHeight)
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.translate((size.width - frame.viewWidth * scale) / 2f, (size.height - frame.viewHeight * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.clipRect(0, 0, frame.viewWidth, frame.viewHeight)
        drawAnalyzedFrame(canvas, frame)
        drawVisionOverlay(canvas, frame, target.detection)
        canvas.restore()
    }
}

fun scanInstruction(phase: ScanPhase): String = when (phase) {
    ScanPhase.BASELINE -> "Keep the phone away from the target, steady and in a fixed orientation."
    ScanPhase.APPROACH -> "Slowly approach the selected physical object. Keep phone orientation fixed."
    ScanPhase.HOLD -> "Hold still at a safe, repeatable distance. Do not touch the object."
    ScanPhase.RETREAT -> "Slowly retreat to the original baseline position."
    else -> "Return to the baseline position before starting the next pass."
}

@Composable
fun ScanControls(targets: TargetAcquisitionModel, model: InstrumentModel) {
    val target = targets.target ?: return
    if (target.decision == MaterialDecision.NONMETAL_SUSPECTED) {
        Text("Visual result only. Magnetic fusion and scan controls are skipped.")
        return
    }
    var consent by remember(target.token) { mutableStateOf(false) }
    val scan = targets.scan
    Text(if (target.decision == MaterialDecision.METAL_SUSPECTED) "Guided magnetic scan" else "Optional manual magnetic scan", color = MaterialTheme.colorScheme.secondary)
    Text("Three passes, each: baseline 3 s → approach 3 s → hold 3 s → retreat 3 s. Keep orientation and distance repeatable; avoid moving nearby objects or changing the torch.")
    Row {
        Checkbox(checked = consent, enabled = !scan.active, onCheckedChange = { consent = it })
        Text("I can scan this physical target under controlled conditions, starting away from it. Surrounding materials may interfere.")
    }
    val reliable = model.available && model.accuracy in 2..3
    if (!reliable) Text("Fresh medium/high-accuracy magnetic readings are required. Calibrate away from interference.")
    if (scan.active) {
        Text("Pass ${scan.pass}/3 • ${scan.phase} • ${String.format(Locale.UK, "%.1f", scan.remaining)} s")
        Text(scanInstruction(scan.phase))
        TextButton(onClick = { targets.interruptScan("Cancelled by user; restart scan") }) { Text("Cancel scan") }
    } else {
        scan.reason?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        Button(enabled = consent && reliable && target.material != MaterialResult.Pending, onClick = {
            targets.beginScan(SystemClock.elapsedRealtimeNanos() / 1e9)
        }) {
            Text(when (scan.phase) {
                ScanPhase.READY_NEXT -> "Begin pass ${scan.pass}/3 at baseline"
                ScanPhase.COMPLETE -> "Repeat three-pass scan"
                else -> "Begin baseline / pass 1 of 3"
            })
        }
        if (scan.phase == ScanPhase.READY_NEXT) {
            Text("Return to the original position, then begin the next baseline.")
            TextButton(onClick = { targets.interruptScan("Cancelled between passes; restart scan") }) { Text("Cancel scan") }
        }
    }
    scanMetricsText(scan.result).forEach { Text(it) }
    Text("Weak response does not prove nonmetal. Association is provisional; surrounding materials and the phone may be responsible.", style = MaterialTheme.typography.bodySmall)
}

fun scanMetricsText(result: GuidedScanResult?): List<String> = result?.let {
    it.metrics.mapIndexed { i, p ->
        String.format(Locale.UK, "Pass %d: baseline %.2f µT • noise σ %.2f µT • peak |Δ| %.2f µT • hold Δ %.2f µT • retreat Δ %.2f µT", i + 1, p.baseline, p.noise, p.peakDeviation, p.holdDeviation, p.retreatResidual)
    } + String.format(Locale.UK, "Repeatability: peak spread %.2f µT • relative spread %s (descriptive ratio)", it.spread,
        it.relativeSpread?.let { ratio -> String.format(Locale.UK, "%.3f", ratio) } ?: "undefined")
} ?: emptyList()
