package uk.co.magpie.instrument.vision

import org.junit.Assert.*
import org.junit.Test

internal fun scanFixture(deltas: List<Double>, baselineStep: Double = 0.0, noise: Double = .1, residual: Double = 0.0): GuidedScanResult {
    val passes = deltas.mapIndexed { index, delta ->
        val base = 50.0 + index * baselineStep
        ScanPass(listOf(ScanPhase.BASELINE, ScanPhase.APPROACH, ScanPhase.HOLD, ScanPhase.RETREAT).mapIndexed { phaseIndex, phase ->
            val start = index * 20.0 + phaseIndex * 3
            ScanPhaseRecord(phase, start, start + 3, List(30) { i ->
                val value = when (phase) {
                    ScanPhase.BASELINE -> base + if (i % 2 == 0) noise else -noise
                    ScanPhase.APPROACH -> base + delta * (i / 20.0).coerceAtMost(1.0)
                    ScanPhase.HOLD -> base + delta
                    else -> base + if (i >= 20) residual else delta * (1 - i / 20.0)
                }
                MagneticSample(start + i / 10.0 + .01, value, 3)
            })
        })
    }
    return summarizeGuidedScan(passes)
}

class GuidedScanTest {
    @Test fun realPhaseAcquisitionRequiresThreeCompletePasses() {
        val engine = GuidedScan()
        for (pass in 0..2) {
            val start = pass * 20.0
            engine.begin(start)
            for (i in 0 until 120) engine.add(MagneticSample(start + i / 10.0 + .01, if (i in 30..89) 55.0 else 50.0, 3))
            engine.tick(start + 12)
            val progress = engine.snapshot(start + 12)
            assertEquals(if (pass == 2) ScanPhase.COMPLETE else ScanPhase.READY_NEXT, progress.phase)
            if (pass < 2) assertNull(progress.result)
        }
        val result = engine.snapshot(52.0).result!!
        assertEquals(3, result.passes.size)
        assertEquals(4, result.passes[0].records.size)
        assertEquals(360, result.passes.sumOf { it.records.sumOf { record -> record.samples.size } })
        assertEquals(50.0, result.metrics[0].baseline, 0.0)
        assertEquals(5.0, result.peak, 0.0)
    }
    @Test fun repeatsOnlyYieldProvisionalHypothesisWithInterferenceCaveat() {
        val result = scanFixture(listOf(5.0, 5.2, 5.1))
        assertEquals(.1, result.metrics[0].noise, 1e-8)
        assertEquals(.2, result.spread, 1e-8)
        val text = interpretScan(result)
        assertTrue(text.contains("provisional hypothesis"))
        assertTrue(text.contains("Surrounding materials"))
        assertTrue(text.contains("cannot establish material identity"))
    }
    @Test fun weakNoisyDriftingOrInconsistentScansRemainInconclusive() {
        val cases = listOf(scanFixture(List(3) { .1 }), scanFixture(listOf(5.0, -5.0, 5.0)),
            scanFixture(List(3) { 5.0 }, baselineStep = 4.0), scanFixture(List(3) { 5.0 }, noise = 3.0),
            scanFixture(List(3) { 5.0 }, residual = 3.0))
        cases.forEach {
            val text = interpretScan(it)
            assertTrue(text.contains("Inconclusive"))
            assertTrue(text.contains("never proof"))
        }
    }
    @Test fun gapsAccuracyLossAndInterruptionsAbortWithoutResults() {
        val engine = GuidedScan()
        engine.begin(0.0)
        engine.add(MagneticSample(.1, 50.0, 3))
        engine.tick(1.0)
        assertEquals(ScanPhase.ABORTED, engine.snapshot(1.0).phase)
        engine.begin(2.0)
        engine.add(MagneticSample(2.1, 50.0, 1))
        assertEquals(ScanPhase.ABORTED, engine.snapshot(2.1).phase)
        engine.begin(3.0)
        engine.abort("Backgrounded")
        assertEquals("Backgrounded", engine.snapshot(3.1).reason)
        assertNull(engine.snapshot(3.1).result)
    }
    @Test fun duplicateOrInsufficientSamplesCannotCompleteAStage() {
        val engine = GuidedScan()
        engine.begin(0.0)
        engine.add(MagneticSample(.1, 50.0, 3))
        engine.add(MagneticSample(.1, 50.0, 3))
        assertEquals(ScanPhase.ABORTED, engine.snapshot(.1).phase)
        engine.begin(1.0)
        for (i in 1..5) engine.add(MagneticSample(1.0 + i * .5, 50.0, 3))
        engine.tick(4.0)
        assertEquals(ScanPhase.ABORTED, engine.snapshot(4.0).phase)
    }
    @Test fun interruptedBetweenPassesRequiresFullRestart() {
        val engine = GuidedScan()
        engine.begin(0.0)
        repeat(120) { engine.add(MagneticSample(it / 10.0 + .01, 50.0, 3)) }
        engine.tick(12.0)
        engine.abort("Left Vision")
        assertEquals(ScanPhase.ABORTED, engine.snapshot(12.0).phase)
        engine.begin(20.0)
        assertEquals(1, engine.snapshot(20.0).pass)
    }
    @Test(expected = IllegalArgumentException::class) fun incompletePhaseRecordsRejected() {
        val result = scanFixture(List(3) { 5.0 })
        summarizeGuidedScan(result.passes.map { pass -> pass.copy(records = pass.records.dropLast(1)) })
    }
}
