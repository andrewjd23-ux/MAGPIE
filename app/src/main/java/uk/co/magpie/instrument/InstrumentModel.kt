package uk.co.magpie.instrument

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import uk.co.magpie.instrument.vision.MagneticSample

class InstrumentModel : ViewModel() {
    var raw by mutableStateOf<Field?>(null); private set
    var smoothed by mutableStateOf<Field?>(null); private set
    var baseline by mutableStateOf<Field?>(null); private set
    var history by mutableStateOf<List<HistoryPoint>>(emptyList()); private set
    var accuracy by mutableStateOf(-1)
    var active by mutableStateOf(false)
    var now by mutableStateOf(0.0); private set
    private var lastReading = Double.NEGATIVE_INFINITY
    private val smoother = FieldSmoother()
    private val observers = mutableSetOf<(MagneticSample) -> Unit>()
    fun observeSamples(observer: (MagneticSample) -> Unit): AutoCloseable {
        observers.add(observer)
        return AutoCloseable { observers.remove(observer) }
    }
    val available: Boolean get() = active && now - lastReading in 0.0..<2.0 && raw != null

    fun tick(seconds: Double) {
        now = seconds
        history = history.filter { it.seconds >= seconds - 60.0 }
    }
    fun accept(field: Field, seconds: Double, measuredAt: Double = seconds) {
        if (!field.isFinite || !field.magnitude.isFinite() || !seconds.isFinite() || !measuredAt.isFinite() || measuredAt > seconds) return
        tick(seconds)
        raw = field
        val filtered = smoother.update(field)
        smoothed = filtered
        lastReading = measuredAt
        observers.toList().forEach { it(MagneticSample(measuredAt, field.magnitude, accuracy)) }
        // Bound rendering work to ten points per second.
        if (seconds - measuredAt < 2.0 && history.lastOrNull()?.let { measuredAt - it.seconds >= 0.1 } != false) {
            history = history + HistoryPoint(measuredAt, field.magnitude, filtered.magnitude)
        }
    }
    fun captureBaseline() { if (available) baseline = smoothed }
    fun resume() { smoother.reset(); active = true; lastReading = Double.NEGATIVE_INFINITY }
    fun pause() { active = false }
    fun reset() {
        baseline = null; raw = null; smoothed = null; history = emptyList()
        smoother.reset(); lastReading = Double.NEGATIVE_INFINITY
    }
}
