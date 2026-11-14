package uk.co.magpie.instrument.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uk.co.magpie.instrument.MainActivity

@RunWith(AndroidJUnit4::class)
class NativeMaterialTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun fixture() = instrumentation.context.assets.open("material_test_cat.bmp").use { BitmapFactory.decodeStream(it)!! }
    private fun reference() = instrumentation.context.assets.open("material_reference.json").bufferedReader().use { JSONObject(it.readText()) }
    private fun assertReference(result: MaterialResult) {
        assertTrue("Actual Android native material inference must return predictions: $result", result is MaterialResult.Predictions)
        val predictions = result as MaterialResult.Predictions
        assertFalse(predictions.calibrationValidated)
        val expected = reference().getJSONArray("cat_scores")
        assertEquals(23, predictions.scores.size)
        val differences = predictions.scores.mapIndexed { i, score ->
            assertEquals(MincMaterialContract.labels[i], score.label)
            kotlin.math.abs(expected.getDouble(i) - score.score)
        }
        // Pixel and normalized-tensor digests are exact. Quantized native kernels in the
        // Android and host builds vary numerically (observed max score delta 0.008371).
        // This bounded regression tolerance is not a confidence/accuracy threshold.
        assertTrue("Native score max delta=${differences.max()}; actual=${predictions.scores}; expected=$expected", differences.max() <= .02)
        val expectedTop = (0 until expected.length()).sortedByDescending { expected.getDouble(it) }.take(3).map { MincMaterialContract.labels[it] }
        assertEquals(expectedTop, predictions.scores.sortedByDescending { it.score }.take(3).map { it.label })
        assertEquals(1.0, predictions.scores.sumOf { it.score.toDouble() }, 1e-5)
    }
    private fun checkFixture(input: MaterialInput) {
        val expected = reference()
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun rgb(pixels: IntArray) = ByteArray(pixels.size * 3).also { bytes ->
            pixels.forEachIndexed { i, pixel ->
                bytes[i * 3] = (pixel shr 16 and 255).toByte()
                bytes[i * 3 + 1] = (pixel shr 8 and 255).toByte()
                bytes[i * 3 + 2] = (pixel and 255).toByte()
            }
        }
        assertEquals("Original RGB decode", expected.getString("cat_original_rgb_sha256"), hash(rgb(input.argb)))
        assertEquals("Resized RGB", expected.getString("cat_resized_rgb_sha256"), hash(rgb(MincPreprocessor.resizeRgb(input))))
        val tensor = MincPreprocessor.tensor(input)
        val bytes = ByteBuffer.allocate(tensor.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        tensor.forEach { bytes.putFloat(it) }
        assertEquals("Normalized NCHW tensor", expected.getString("cat_tensor_sha256"), hash(bytes.array()))
    }
    @Test fun bundledModelRunsInAndroidNativeRuntimeWithoutInternetPermission() {
        val context = instrumentation.targetContext
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        val image = fixture()
        try {
            val argb = IntArray(image.width * image.height)
            image.getPixels(argb, 0, image.width, 0, 0, image.width, image.height)
            checkFixture(MaterialInput(argb, image.width, image.height))
            ObjectDetector(context.assets).use { detector ->
                assertTrue(detector.detect(image).any { it.label == "cat" && it.score >= .35f })
                OnnxMaterialClassifier(context).use { classifier ->
                    assertTrue(classifier.status is MaterialModelStatus.Ready)
                    assertReference(classifier.classify(MaterialInput(argb, image.width, image.height)))
                }
                // Both native runtimes coexist; material inference must not break EfficientDet.
                assertTrue(detector.detect(image).any { it.label == "cat" && it.score >= .35f })
            }
        } finally { image.recycle() }
    }
    @Test fun actualAcquisitionUsesCropPixelsAndRetainsPredictionsAfterActivityRecreation() {
        val image = fixture()
        val detection = Detection("knife", .99f, Box2(0f, 0f, image.width.toFloat(), image.height.toFloat()))
        // Deliberately different object label: material output must follow image pixels, not COCO.
        val frame = VisionFrame(image, listOf(detection), Matrix(), image.width, image.height, SystemClock.elapsedRealtime(), 0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original: TargetAcquisitionModel
            scenario.onActivity { activity ->
                original = ViewModelProvider(activity)[TargetAcquisitionModel::class.java]
                original.acquire(frame, detection)
            }
            val deadline = SystemClock.elapsedRealtime() + 90000
            var acquired: AcquiredTarget? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { acquired = original.target }
                if (acquired?.material != MaterialResult.Pending) break
                SystemClock.sleep(50)
            }
            assertReference(acquired!!.material)
            assertNotNull(acquired!!.crop)
            val stable = acquired!!
            scenario.recreate()
            scenario.onActivity { activity ->
                val restored = ViewModelProvider(activity)[TargetAcquisitionModel::class.java]
                assertSame(original, restored)
                assertSame(stable.frame, restored.target!!.frame)
                assertSame(stable.crop, restored.target!!.crop)
                assertEquals(stable.material, restored.target!!.material)
                assertEquals(stable.token, restored.target!!.token)
                restored.release()
                assertNull(restored.target)
            }
        }
        // The acquired bitmap is no longer rendered after the Activity has closed.
        image.recycle()
    }
}
