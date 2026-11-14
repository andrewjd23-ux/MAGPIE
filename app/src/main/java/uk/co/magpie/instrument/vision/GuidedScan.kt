package uk.co.magpie.instrument.vision

import kotlin.math.abs
import kotlin.math.sqrt

data class MagneticSample(val seconds: Double, val magnitude: Double, val accuracy: Int)
enum class ScanPhase { IDLE, BASELINE, APPROACH, HOLD, RETREAT, READY_NEXT, COMPLETE, ABORTED }
data class ScanPhaseRecord(val phase: ScanPhase, val start: Double, val end: Double, val samples: List<MagneticSample>)
data class ScanPass(val records: List<ScanPhaseRecord>)
data class PassMetrics(val baseline: Double, val noise: Double, val peakDeviation: Double, val holdDeviation: Double, val retreatResidual: Double)
data class GuidedScanResult(val passes: List<ScanPass>, val metrics: List<PassMetrics>) {
    val peak get() = metrics.maxOf { it.peakDeviation }
    val spread get() = metrics.maxOf { it.peakDeviation } - metrics.minOf { it.peakDeviation }
    val relativeSpread get() = metrics.map { it.peakDeviation }.average().takeIf { it > 0 }?.let { spread / it }
}
data class ScanProgress(val phase: ScanPhase, val pass: Int, val remaining: Double, val reason: String?, val result: GuidedScanResult?) {
    val active get() = phase in listOf(ScanPhase.BASELINE, ScanPhase.APPROACH, ScanPhase.HOLD, ScanPhase.RETREAT)
}

/** Main-thread state machine; keeps raw samples with timestamps/accuracy for all 3 × 4 phases. */
class GuidedScan(private val phaseSeconds: Double = 3.0) {
    init { require(phaseSeconds >= 1.0) }
    private var phase = ScanPhase.IDLE
    private var start = 0.0
    private var lastSample = Double.NEGATIVE_INFINITY
    private var samples = mutableListOf<MagneticSample>()
    private var records = mutableListOf<ScanPhaseRecord>()
    private var passes = mutableListOf<ScanPass>()
    private var reason: String? = null
    private var result: GuidedScanResult? = null
    fun begin(now: Double) {
        require(now.isFinite())
        if (phase != ScanPhase.READY_NEXT) { passes.clear(); result = null }
        records.clear(); samples.clear(); phase = ScanPhase.BASELINE
        start = now; lastSample = now; reason = null
    }
    fun clear() { phase = ScanPhase.IDLE; passes.clear(); records.clear(); samples.clear(); reason = null; result = null }
    fun abort(message: String) { if (active() || phase == ScanPhase.READY_NEXT) { phase = ScanPhase.ABORTED; reason = message; result = null } }
    private fun active() = phase in listOf(ScanPhase.BASELINE, ScanPhase.APPROACH, ScanPhase.HOLD, ScanPhase.RETREAT)
    fun add(sample: MagneticSample) {
        if (!active()) return
        if (!sample.seconds.isFinite() || !sample.magnitude.isFinite() || sample.magnitude < 0 || sample.accuracy !in 2..3) {
            abort("Invalid or low-accuracy magnetic reading. Recalibrate and restart."); return
        }
        if (sample.seconds < start || sample.seconds <= lastSample && samples.isNotEmpty()) {
            abort("Out-of-order magnetic samples. Restart the scan."); return
        }
        if (sample.seconds - lastSample > .75) { abort("Magnetic sample gap exceeded 0.75 s. Restart the scan."); return }
        advance(sample.seconds)
        if (active()) { samples.add(sample); lastSample = sample.seconds }
    }
    fun tick(now: Double) {
        if (!active()) return
        if (!now.isFinite() || now < start) { abort("Invalid scan clock; restart scan"); return }
        if (now - lastSample > .75) { abort("Magnetic readings interrupted. Restart the scan."); return }
        advance(now)
    }
    private fun advance(now: Double) {
        if (!active() || now < start + phaseSeconds) return
        if (samples.size < 10) { abort("Too few samples in $phase. Restart with a faster sensor."); return }
        records.add(ScanPhaseRecord(phase, start, start + phaseSeconds, samples.toList()))
        samples = mutableListOf()
        start += phaseSeconds
        phase = when (phase) {
            ScanPhase.BASELINE -> ScanPhase.APPROACH
            ScanPhase.APPROACH -> ScanPhase.HOLD
            ScanPhase.HOLD -> ScanPhase.RETREAT
            else -> {
                passes.add(ScanPass(records.toList()))
                if (passes.size == 3) {
                    result = summarizeGuidedScan(passes.toList()); ScanPhase.COMPLETE
                } else ScanPhase.READY_NEXT
            }
        }
    }
    fun snapshot(now: Double) = ScanProgress(phase, (passes.size + 1).coerceAtMost(3),
        if (active()) (start + phaseSeconds - now).coerceAtLeast(0.0) else 0.0, reason, result)
}

fun summarizeGuidedScan(passes: List<ScanPass>): GuidedScanResult {
    require(passes.size == 3)
    val metrics = passes.map { pass ->
        require(pass.records.map { it.phase } == listOf(ScanPhase.BASELINE, ScanPhase.APPROACH, ScanPhase.HOLD, ScanPhase.RETREAT))
        pass.records.forEach { record ->
            require(record.samples.size >= 10 && record.start.isFinite() && record.end.isFinite() && record.end > record.start)
            require(record.samples.first().seconds - record.start <= .75 && record.end - record.samples.last().seconds <= .75)
            require(record.samples.all { it.seconds >= record.start && it.seconds < record.end && it.magnitude.isFinite() && it.magnitude >= 0 && it.accuracy in 2..3 })
            require(record.samples.zipWithNext().all { (a, b) -> b.seconds > a.seconds && b.seconds - a.seconds <= .75 })
        }
        require(pass.records.zipWithNext().all { (a, b) -> abs(a.end - b.start) < 1e-6 })
        val baselineSamples = pass.records[0].samples.map { it.magnitude }
        val baseline = baselineSamples.average()
        val noise = sqrt(baselineSamples.sumOf { (it - baseline) * (it - baseline) } / baselineSamples.size)
        val hold = pass.records[2].samples.map { it.magnitude - baseline }.average()
        val retreat = pass.records[3].samples.takeLast(10).map { it.magnitude - baseline }.average()
        val peak = pass.records.drop(1).flatMap { it.samples }.maxOf { abs(it.magnitude - baseline) }
        PassMetrics(baseline, noise, peak, hold, retreat)
    }
    return GuidedScanResult(passes, metrics)
}

/** Conservative heuristic evidence description, never a metal detector or probability. */
fun interpretScan(result: GuidedScanResult): String {
    val metrics = result.metrics
    val baselineDrift = metrics.maxOf { it.baseline } - metrics.minOf { it.baseline }
    val thresholds = metrics.map { maxOf(1.0, it.noise * 3.0) } // µT floor plus measured noise gate
    val holds = metrics.map { it.holdDeviation }
    val sameSign = holds.all { it > 0 } || holds.all { it < 0 }
    val repeatable = metrics.indices.all { abs(holds[it]) >= thresholds[it] && abs(metrics[it].retreatResidual) <= thresholds[it] } &&
        sameSign && (result.relativeSpread ?: Double.POSITIVE_INFINITY) <= .5 && baselineDrift <= thresholds.max()
    val response = if (repeatable) "A repeatable response during the controlled passes is a provisional hypothesis of association with the selected target."
        else "Inconclusive: weak, noisy, inconsistent or drifting magnetic response. A weak response is never proof that the target is nonmetallic."
    return "$response Surrounding materials, phone components, movement and other interference may explain the response. Magnetic measurements cannot establish material identity."
}
