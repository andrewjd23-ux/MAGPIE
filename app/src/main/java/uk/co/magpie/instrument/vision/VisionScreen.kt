package uk.co.magpie.instrument.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.magpie.instrument.InstrumentModel
import uk.co.magpie.instrument.baselineDeviation
import java.util.Locale

private fun Double.formatField() = String.format(Locale.UK, "%.2f", this)

@androidx.annotation.OptIn(TransformExperimental::class)
@Composable
fun VisionScreen(model: InstrumentModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requested by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; requested = true }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var status by remember { mutableStateOf("Camera permission is required for Vision") }
    var frame by remember { mutableStateOf<VisionFrame?>(null) }
    var selected by remember { mutableStateOf<Detection?>(null) }
    var frozen by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<VisionCameraSession?>(null) }
    var captureMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) { now = SystemClock.elapsedRealtime(); delay(250) }
    }
    val displayed = frame?.takeIf { frozen || now - it.receivedAt < 1500 }
    val currentFrame by rememberUpdatedState(displayed)
    val displayedSelection = selected.takeIf { displayed != null }
    val material = remember { NoMaterialModel().unavailable() }
    val magnetic = if (model.available) listOf(
        "Smoothed |B| ${model.smoothed!!.magnitude.formatField()} µT",
        "Raw |B| ${model.raw!!.magnitude.formatField()} µT",
        "Δ baseline ${model.baseline?.let { baselineDeviation(model.smoothed!!, it).formatField() + " µT" } ?: "not captured"}"
    ) else listOf("Magnetic readings unavailable", "Δ baseline unavailable")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val footerMaxHeight = (maxHeight * .45f).coerceAtMost(290.dp)
        Column(Modifier.fillMaxSize()) {
            Text("MAGPIE VISION", color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                if (granted) {
                    val view = remember(context) {
                        PreviewView(context).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                    }
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    DisposableEffect(view, owner) {
                        val camera = VisionCameraSession(context, owner, view,
                            onFrame = { value ->
                                frame = value
                                selected = matchSelection(selected, value?.detections ?: emptyList())
                                if (value == null) frozen = false
                            },
                            onStatus = { status = it },
                            onTorch = { available, on -> hasTorch = available; torchOn = on })
                        session = camera
                        onDispose { camera.close(); session = null }
                    }
                    Canvas(Modifier.matchParentSize().semantics {
                        contentDescription = "Rear camera object detections. Tap a box to select a region."
                    }.pointerInput(Unit) {
                        detectTapGestures { p ->
                            selected = currentFrame?.detections?.filter { it.box.contains(p.x, p.y) }
                                ?.minByOrNull { it.box.width * it.box.height }
                        }
                    }) {
                        val result = displayed ?: return@Canvas
                        if (result.viewWidth != size.width.toInt() || result.viewHeight != size.height.toInt()) return@Canvas
                        val canvas = drawContext.canvas.nativeCanvas
                        canvas.save()
                        canvas.clipRect(0f, 0f, size.width, size.height)
                        if (frozen) drawAnalyzedFrame(canvas, result)
                        drawVisionOverlay(canvas, result, displayedSelection)
                        canvas.restore()
                    }
                } else {
                    Column(Modifier.padding(20.dp)) {
                        Text("Rear-camera preview needs camera permission. The instrument works without it.")
                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Enable rear camera") }
                        if (requested) TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }) { Text("Open app permissions") }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = footerMaxHeight).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (!granted) "Camera permission is required for Vision" else if (frozen) "FROZEN ANALYZED FRAME" else if (frame != null && displayed == null) "Analysis is slow or stale — boxes hidden" else status,
                    color = MaterialTheme.colorScheme.secondary)
                Text(magnetic.joinToString("\n"), color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                Text("Magnetic values stay live when the image is frozen.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = displayed != null, onClick = {
                        frozen = !frozen; session?.setFrozen(frozen)
                    }) { Text(if (frozen) "Resume" else "Freeze") }
                    OutlinedButton(enabled = hasTorch, onClick = { session?.toggleTorch() }) { Text(if (torchOn) "Torch off" else "Torch") }
                    OutlinedButton(enabled = displayed != null && !saving, onClick = {
                        val snapshot = displayed ?: return@OutlinedButton
                        val selection = displayedSelection
                        val magneticSnapshot = magnetic.toList()
                        saving = true
                        scope.launch {
                            try {
                                captureMessage = withContext(Dispatchers.IO) { saveVisionCapture(context, snapshot, selection, magneticSnapshot) }
                            } catch (error: Exception) { captureMessage = "Capture failed: ${error.message}" }
                            finally { saving = false }
                        }
                    }) { Text(if (saving) "Saving…" else "Capture") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = model.available, onClick = model::captureBaseline) { Text("Capture baseline") }
                    TextButton(onClick = model::reset) { Text("Reset instrument") }
                }
                captureMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                Text(displayedSelection?.let { "Selected region: ${it.label} • uncalibrated score ${String.format(Locale.UK, "%.2f", it.score)}" }
                    ?: "Tap a detected box to select a region. Selection is not object tracking.")
                Text("EfficientDet-Lite0 • COCO object labels • uncalibrated scores", style = MaterialTheme.typography.bodySmall)
                Text(explainEvidence(displayedSelection?.label, material), color = MaterialTheme.colorScheme.secondary)
                Text("Three-pass guided scanning is not available in this increment. Torch operation, phone motion and nearby sources can change the magnetic field.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
