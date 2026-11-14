package uk.co.magpie.instrument

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val Green = Color(0xFFA6C6A0)
private val Amber = Color(0xFFD4AB62)
private val Panel = Color(0xFF19231F)
private fun Double.reading() = String.format(Locale.UK, "%.2f", this)
private fun Field.axes() = "X ${x.reading()}   Y ${y.reading()}   Z ${z.reading()} µT"
private fun accuracyLabel(value: Int) = when (value) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW — calibrate away from interference"
    SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
    else -> "UNKNOWN"
}

@Composable
fun InstrumentScreen(model: InstrumentModel, sensor: Sensor?) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Green, secondary = Amber, background = Color(0xFF0E1512), surface = Panel)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("MAGPIE", color = Amber, fontSize = 30.sp, fontFamily = FontFamily.Monospace)
                Text("Magnetic Anomaly & Geomagnetic\nPocket Instrument Explorer", color = Green)
                HorizontalDivider(color = Amber)
                val ready = model.available
                Text(when {
                    sensor == null -> "NO MAGNETOMETER — this device has no magnetic field sensor."
                    !model.active -> "SENSOR UNAVAILABLE — monitoring paused or listener could not start."
                    !ready -> "WAITING FOR SENSOR — no recent reading."
                    else -> "LIVE • FIELD STRENGTH"
                }, color = Amber)
                Text(if (ready) "${model.smoothed!!.magnitude.reading()} µT" else "— µT", fontSize = 44.sp, color = Green, fontFamily = FontFamily.Monospace)
                Text("SMOOTHED MAGNITUDE • EMA α = 0.20", color = Green)
                Gauge(if (ready) model.smoothed?.magnitude else null)
                Text("Gauge scale 0–100 µT • values above scale saturate", color = Green)
                Readout("RAW", model.raw.takeIf { ready })
                Readout("SMOOTHED", model.smoothed.takeIf { ready })
                val baseline = model.baseline
                Text("BASELINE: ${baseline?.magnitude?.reading() ?: "not captured"}${if (baseline != null) " µT" else ""}", color = Amber)
                Text("Δ strength: ${if (ready && baseline != null) baselineDeviation(model.smoothed!!, baseline).reading() + " µT" else "—"}", color = Green)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = model::captureBaseline, enabled = ready) { Text("Capture baseline") }
                    OutlinedButton(onClick = model::reset) { Text("Reset") }
                }
                Text("60 SECOND HISTORY • µT", color = Amber)
                val upper = maxOf(100.0, (model.history.maxOfOrNull { maxOf(it.raw, it.smoothed) } ?: 0.0) * 1.1)
                Text("${upper.reading()} µT", color = Green)
                HistoryChart(model.history, model.now, upper)
                Text("0 µT     −60 s                           now\nAmber: raw   Green: smoothed", color = Green, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = Amber)
                Text("SENSOR DOSSIER", color = Amber)
                Text(sensor?.let { "Name: ${it.name}\nVendor: ${it.vendor}\nResolution: ${it.resolution} µT\nMaximum range: ${it.maximumRange} µT" } ?: "Sensor metadata unavailable", color = Green)
                Text("Accuracy: ${accuracyLabel(model.accuracy)}", color = Amber)
                Text("Axes follow the device sensor coordinates: X right, Y toward the top, Z out of the screen. Readings do not identify the source of a field change. Reset clears the baseline, filter and history. Monitoring runs only while this Activity is resumed.", color = Green)
            }
        }
    }
}

@Composable
private fun Readout(label: String, field: Field?) {
    Column(Modifier.fillMaxWidth().background(Panel).padding(12.dp)) {
        Text("$label • |B| ${field?.magnitude?.reading() ?: "—"} µT", color = Amber, fontFamily = FontFamily.Monospace)
        Text(field?.axes() ?: "X —   Y —   Z — µT", color = Green, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Gauge(value: Double?) {
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = "Magnetic field gauge, ${value?.reading() ?: "unavailable"} microteslas" }) {
        val radius = minOf(size.width * .43f, size.height * .85f)
        val center = Offset(size.width / 2, size.height * .93f)
        fun point(angle: Double, r: Float) = Offset(center.x + cos(angle).toFloat() * r, center.y + sin(angle).toFloat() * r)
        for (i in 0..50) {
            val angle = Math.PI + Math.PI * i / 50
            drawLine(if (i % 5 == 0) Amber else Green.copy(alpha = .45f), point(angle, radius * if (i % 5 == 0) .86f else .94f), point(angle, radius), 2f)
        }
        value?.let {
            val angle = Math.PI + Math.PI * (it / 100).coerceIn(0.0, 1.0)
            drawLine(Amber, center, point(angle, radius * .8f), 4f, StrokeCap.Round)
        }
        drawCircle(Amber, 7f, center)
    }
}

@Composable
private fun HistoryChart(points: List<HistoryPoint>, now: Double, upper: Double) {
    Canvas(Modifier.fillMaxWidth().height(160.dp).background(Panel).semantics { contentDescription = "Rolling 60 second magnetic field history; amber raw, green smoothed" }) {
        for (i in 0..4) {
            val y = size.height * i / 4
            drawLine(Green.copy(alpha = .15f), Offset(0f, y), Offset(size.width, y))
        }
        fun trace(smoothed: Boolean) {
            val path = Path()
            var previous: Double? = null
            points.forEach { p ->
                val x = ((p.seconds - now + 60) / 60 * size.width).toFloat()
                val y = (size.height * (1 - (if (smoothed) p.smoothed else p.raw) / upper)).toFloat()
                if (previous == null || p.seconds - previous!! > 2) path.moveTo(x, y) else path.lineTo(x, y)
                previous = p.seconds
            }
            drawPath(path, if (smoothed) Green else Amber.copy(alpha = .65f), style = Stroke(if (smoothed) 3f else 1.5f))
        }
        trace(false); trace(true)
    }
}
