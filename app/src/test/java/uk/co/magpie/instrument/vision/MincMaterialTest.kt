package uk.co.magpie.instrument.vision

import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class MincMaterialTest {
    @Test fun resizingMatchesPublishedPillowBilinearInBothDirectionsAndAtEdges() {
        val expected = Properties().apply {
            MincMaterialTest::class.java.getResourceAsStream("/material/resize.properties")!!.use { load(it) }
        }
        for ((w, h) in listOf(37 to 19, 389 to 271, 224 to 224, 1 to 1)) {
            val pixels = IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                (255 shl 24) or (((x * 13 + y * 7) % 256) shl 16) or (((x * 3 + y * 17) % 256) shl 8) or ((x * 19 + y * 5) % 256)
            }
            val resized = MincPreprocessor.resizeRgb(MaterialInput(pixels, w, h))
            val rgb = ByteArray(224 * 224 * 3)
            resized.forEachIndexed { i, pixel ->
                rgb[i * 3] = (pixel shr 16 and 255).toByte()
                rgb[i * 3 + 1] = (pixel shr 8 and 255).toByte()
                rgb[i * 3 + 2] = (pixel and 255).toByte()
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(rgb).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals("$w x $h resize", expected.getProperty("${w}x$h"), actual)
        }
    }
    @Test fun rgbNormalizationAndPlanarLayoutMatchTrainingContract() {
        val tensor = MincPreprocessor.tensor(MaterialInput(intArrayOf(0xFFFF8000.toInt()), 1, 1))
        val area = 224 * 224
        assertEquals(area * 3, tensor.size)
        assertTrue(tensor.take(area).all { it == 1f })
        assertEquals((128f / 255f - .5f) / .5f, tensor[area], 1e-7f)
        assertTrue(tensor.drop(area * 2).all { it == -1f })
    }
    @Test fun softmaxIsStableUncalibratedAndUsesActualMaterialVocabulary() {
        val logits = FloatArray(23) { -10000f }.apply { this[9] = 10000f }
        val result = MincMaterialContract.predictions(logits)
        assertEquals("metal", result.scores.maxBy { it.score }.label)
        assertEquals(1f, result.scores.sumOf { it.score.toDouble() }.toFloat(), 1e-6f)
        assertFalse(result.calibrationValidated)
        assertEquals(23, result.scores.size)
        assertFalse(result.scores.any { it.label == "knife" })
    }
    @Test fun ambiguousAppearanceLabelsDoNotEstablishNonmetal() {
        listOf("mirror", "painted", "other", "sky").forEach {
            assertEquals(MaterialFamily.UNSPECIFIED, MincMaterialContract.families[it])
        }
        assertEquals(MaterialFamily.METAL, MincMaterialContract.families["metal"])
    }
    @Test(expected = IllegalArgumentException::class) fun invalidNativeLogitsFailClosed() {
        MincMaterialContract.predictions(FloatArray(23) { Float.NaN })
    }
}
