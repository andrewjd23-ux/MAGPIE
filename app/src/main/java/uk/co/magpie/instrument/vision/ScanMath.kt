package uk.co.magpie.instrument.vision

import kotlin.math.abs
import kotlin.math.sqrt

/** Calculation foundation only; guided acquisition and object association are not enabled yet. */
data class ScanSummary(
    val baseline: Double,
    val noiseStandardDeviation: Double,
    val passPeakDeviations: List<Double>,
    val peakDeviation: Double,
    val peakSpread: Double,
    val relativePeakSpread: Double?
)

fun summarizeThreePassScan(baselineReadings: List<Double>, passes: List<List<Double>>): ScanSummary {
    require(baselineReadings.size >= 10) { "At least ten baseline samples are required" }
    require(passes.size == 3 && passes.all { it.size >= 10 }) { "Three complete passes are required" }
    require((baselineReadings + passes.flatten()).all { it.isFinite() && it >= 0.0 })
    val baseline = baselineReadings.average()
    val noise = sqrt(baselineReadings.sumOf { (it - baseline) * (it - baseline) } / baselineReadings.size)
    val peaks = passes.map { pass -> pass.maxOf { abs(it - baseline) } }
    val spread = peaks.max() - peaks.min()
    val average = peaks.average()
    return ScanSummary(baseline, noise, peaks, peaks.max(), spread, if (average > 0) spread / average else null)
}
