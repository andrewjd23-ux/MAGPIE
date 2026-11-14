package uk.co.magpie.instrument

import kotlin.math.sqrt

data class Field(val x: Double, val y: Double, val z: Double) {
    val magnitude: Double get() = sqrt(x * x + y * y + z * z)
    val isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()
}

/** Exponential moving average of axes; first sample seeds the filter. */
class FieldSmoother(private val alpha: Double = 0.2) {
    init { require(alpha > 0.0 && alpha <= 1.0) }
    private var previous: Field? = null
    fun update(raw: Field): Field {
        require(raw.isFinite)
        val old = previous
        return (if (old == null) raw else Field(
            old.x + alpha * (raw.x - old.x),
            old.y + alpha * (raw.y - old.y),
            old.z + alpha * (raw.z - old.z)
        )).also { previous = it }
    }
    fun reset() { previous = null }
}

/** Signed change in field strength, not a source classification. */
fun baselineDeviation(field: Field, baseline: Field): Double = field.magnitude - baseline.magnitude

data class HistoryPoint(val seconds: Double, val raw: Double, val smoothed: Double)
