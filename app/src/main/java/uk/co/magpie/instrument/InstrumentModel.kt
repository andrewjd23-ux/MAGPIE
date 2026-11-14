package uk.co.magpie.instrument

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

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
    val available: Boolean get() = active && now - lastReading < 2.0 && raw != null

    fun tick(seconds: Double) {
        now = seconds
        history = history.filter { it.seconds >= seconds - 60.0 }
    }
    fun accept(field: Field, seconds: Double) {
        if (!field.isFinite) return
        tick(seconds)
        raw = field
        val filtered = smoother.update(field)
        smoothed = filtered
        lastReading = seconds
        // Bound rendering work to ten points per second.
        if (history.lastOrNull()?.let { seconds - it.seconds >= 0.1 } != false) {
            history = history + HistoryPoint(seconds, field.magnitude, filtered.magnitude)
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
