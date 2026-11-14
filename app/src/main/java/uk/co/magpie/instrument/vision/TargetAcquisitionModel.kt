package uk.co.magpie.instrument.vision

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class AcquiredTarget(
    val token: Long,
    val frame: VisionFrame,
    val detection: Detection,
    val sourceRegion: PixelCrop,
    val crop: Bitmap? = null,
    val material: MaterialResult = MaterialResult.Pending,
    val decision: MaterialDecision = MaterialDecision.UNKNOWN
)

/** Independent from camera frames and InstrumentModel. Bitmaps survive configuration changes. */
class TargetAcquisitionModel(private val classifier: MaterialClassifier = NoMaterialModel()) : ViewModel() {
    var target by mutableStateOf<AcquiredTarget?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var scan by mutableStateOf(ScanProgress(ScanPhase.IDLE, 1, 0.0, null, null)); private set
    private val selection = TargetSelection()
    private val scanner = GuidedScan()
    private val executor = Executors.newSingleThreadExecutor()
    private val materialDispatcher = executor.asCoroutineDispatcher()

    fun acquire(frame: VisionFrame, detection: Detection) {
        val values = FloatArray(9).also(frame.imageToView::getValues)
        if (values[6] != 0f || values[7] != 0f || values[8] != 1f) {
            message = "Cannot acquire target: unsupported image transform"; return
        }
        val transform = Affine2(values[0], values[1], values[2], values[3], values[4], values[5])
        val crop = imageCrop(detection.box, transform, frame.bitmap.width, frame.bitmap.height)
        if (crop == null) { message = "Cannot acquire target: invalid crop"; return }
        val token = selection.acquire(detection)
        scanner.clear(); refreshScan(); message = null
        target = AcquiredTarget(token, frame, detection, crop)
        // Do not cancel older native inference mid-call; serialize and discard superseded results.
        viewModelScope.launch {
            try {
                val completed = withContext(materialDispatcher) {
                    if (!selection.accepts(token)) return@withContext null
                    val image = Bitmap.createBitmap(frame.bitmap, crop.left, crop.top, crop.width, crop.height)
                    val analysis = analyzeSelectedCrop(classifier) {
                        val argb = IntArray(image.width * image.height)
                        image.getPixels(argb, 0, image.width, 0, 0, image.width, image.height)
                        MaterialInput(argb, image.width, image.height)
                    }
                    Triple(image, analysis.result, analysis.decision)
                } ?: return@launch
                val (image, result, decision) = completed
                if (selection.accepts(token)) target = target?.copy(crop = image, material = result, decision = decision)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { classificationFailed(token, error) }
            catch (error: LinkageError) { classificationFailed(token, error) }
        }
    }
    private fun classificationFailed(token: Long, error: Throwable) {
        if (selection.accepts(token)) target = target?.copy(material = MaterialResult.Unavailable("Material model unavailable: ${error.message ?: error.javaClass.simpleName}"))
    }
    fun release() { selection.release(); target = null; scanner.clear(); refreshScan(); message = null }
    fun beginScan(now: Double) {
        val acquired = target ?: return
        if (acquired.material == MaterialResult.Pending) return
        if (acquired.decision == MaterialDecision.NONMETAL_SUSPECTED) {
            message = "Visual result only: magnetic fusion is skipped"; return
        }
        scanner.begin(now); refreshScan(now)
    }
    fun onMagneticSample(sample: MagneticSample) { scanner.add(sample); refreshScan(sample.seconds) }
    fun tick(now: Double) { scanner.tick(now); refreshScan(now) }
    fun interruptScan(reason: String) { scanner.abort(reason); refreshScan() }
    private fun refreshScan(now: Double = SystemClock.elapsedRealtimeNanos() / 1e9) { scan = scanner.snapshot(now) }
    override fun onCleared() {
        selection.release()
        // Already queued native inference completes before native resources close.
        executor.execute { classifier.close() }
        materialDispatcher.close()
        super.onCleared()
    }
}
