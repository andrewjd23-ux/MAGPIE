package uk.co.magpie.instrument

import org.junit.Assert.*
import org.junit.Test

class InstrumentModelTest {
    @Test fun baselineRequiresFreshForegroundReading() {
        val model = InstrumentModel()
        model.resume()
        model.captureBaseline()
        assertNull(model.baseline)
        model.accept(Field(3.0, 4.0, 0.0), 10.0)
        model.captureBaseline()
        assertEquals(5.0, model.baseline!!.magnitude, 1e-9)
        model.tick(12.1)
        assertFalse(model.available)
        model.pause()
        assertFalse(model.available)
        model.resume()
        assertFalse(model.available)
        assertNotNull(model.baseline)
    }
    @Test fun historyExpiresAndResetClearsState() {
        val model = InstrumentModel()
        model.resume()
        model.accept(Field(1.0, 0.0, 0.0), 1.0)
        model.accept(Field(2.0, 0.0, 0.0), 62.0)
        assertEquals(1, model.history.size)
        model.captureBaseline()
        model.reset()
        assertNull(model.raw)
        assertNull(model.smoothed)
        assertNull(model.baseline)
        assertTrue(model.history.isEmpty())
        model.accept(Field(9.0, 0.0, 0.0), 63.0)
        assertEquals(9.0, model.smoothed!!.magnitude, 1e-9)
    }
}
