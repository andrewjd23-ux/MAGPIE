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
    @Test fun scanObserverReceivesRawSamplesWithoutChangingInstrumentBaseline() {
        val model = InstrumentModel()
        model.resume()
        model.accuracy = 3
        val samples = mutableListOf<uk.co.magpie.instrument.vision.MagneticSample>()
        val subscription = model.observeSamples { samples.add(it) }
        model.accept(Field(3.0, 4.0, 0.0), 1.0)
        model.captureBaseline()
        model.accept(Field(0.0, 0.0, 13.0), 2.0)
        assertEquals(13.0, samples.last().magnitude, 1e-9)
        assertEquals(3, samples.last().accuracy)
        assertEquals(5.0, model.baseline!!.magnitude, 1e-9)
        subscription.close()
        model.accept(Field(0.0, 0.0, 8.0), 3.0)
        assertEquals(2, samples.size)
        assertEquals(8.0, model.raw!!.magnitude, 1e-9)
    }

    @Test fun delayedSensorEventsAreNotPresentedAsFreshScanSamples() {
        val model = InstrumentModel()
        model.resume()
        val samples = mutableListOf<uk.co.magpie.instrument.vision.MagneticSample>()
        val subscription = model.observeSamples { samples.add(it) }
        model.accept(Field(3.0, 4.0, 0.0), seconds = 10.0, measuredAt = 7.0)
        assertFalse(model.available)
        assertEquals(7.0, samples.single().seconds, 0.0)
        assertTrue(model.history.isEmpty())
        subscription.close()
    }

}
