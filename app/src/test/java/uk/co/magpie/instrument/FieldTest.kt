package uk.co.magpie.instrument

import org.junit.Assert.*
import org.junit.Test

class FieldTest {
    @Test fun magnitudeUsesAllThreeAxes() {
        assertEquals(13.0, Field(3.0, -4.0, 12.0).magnitude, 1e-9)
        assertEquals(0.0, Field(0.0, 0.0, 0.0).magnitude, 0.0)
    }
    @Test fun smoothingSeedsThenFiltersEachAxis() {
        val filter = FieldSmoother(0.25)
        assertEquals(Field(0.0, 4.0, -4.0), filter.update(Field(0.0, 4.0, -4.0)))
        assertEquals(Field(2.0, 3.0, -2.0), filter.update(Field(8.0, 0.0, 4.0)))
        filter.reset()
        assertEquals(Field(8.0, 0.0, 4.0), filter.update(Field(8.0, 0.0, 4.0)))
    }
    @Test fun baselineDeviationIsSignedMagnitudeDifference() {
        val baseline = Field(0.0, 3.0, 4.0)
        assertEquals(8.0, baselineDeviation(Field(3.0, 4.0, 12.0), baseline), 1e-9)
        assertEquals(-5.0, baselineDeviation(Field(0.0, 0.0, 0.0), baseline), 1e-9)
        assertEquals(0.0, baselineDeviation(Field(5.0, 0.0, 0.0), baseline), 1e-9)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidSmoothingFactorRejected() { FieldSmoother(0.0) }
    @Test(expected = IllegalArgumentException::class) fun nonFiniteSampleRejected() { FieldSmoother().update(Field(Double.NaN, 0.0, 0.0)) }
}
