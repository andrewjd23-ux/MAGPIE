package uk.co.magpie.instrument.vision

import org.junit.Assert.*
import org.junit.Test

class ScanMathTest {
    @Test fun recordsBaselineNoisePeaksAndRepeatabilityWithoutProbabilities() {
        val baseline = List(10) { if (it % 2 == 0) 49.0 else 51.0 }
        val result = summarizeThreePassScan(baseline, listOf(List(10) { 55.0 }, List(10) { 44.0 }, List(10) { 55.5 }))
        assertEquals(50.0, result.baseline, 1e-9)
        assertEquals(1.0, result.noiseStandardDeviation, 1e-9)
        assertEquals(listOf(5.0, 6.0, 5.5), result.passPeakDeviations)
        assertEquals(6.0, result.peakDeviation, 1e-9)
        assertEquals(1.0, result.peakSpread, 1e-9)
        assertEquals(1.0 / 5.5, result.relativePeakSpread!!, 1e-9)
    }
    @Test fun noResponseHasNoInventedRepeatabilityRatio() {
        val result = summarizeThreePassScan(List(10) { 50.0 }, List(3) { List(10) { 50.0 } })
        assertEquals(0.0, result.peakDeviation, 0.0)
        assertNull(result.relativePeakSpread)
    }
    @Test(expected = IllegalArgumentException::class) fun partialScansRejected() {
        summarizeThreePassScan(List(10) { 50.0 }, List(2) { List(10) { 50.0 } })
    }
    @Test(expected = IllegalArgumentException::class) fun invalidSamplesRejected() {
        summarizeThreePassScan(List(10) { Double.NaN }, List(3) { List(10) { 50.0 } })
    }
}
