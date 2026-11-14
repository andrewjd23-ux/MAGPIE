package uk.co.magpie.instrument.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/** UI positions and bitmap transform belong to the same viewport generation. */
data class VisionFrame(
    val bitmap: Bitmap,
    val detections: List<Detection>,
    val imageToView: Matrix,
    val viewWidth: Int,
    val viewHeight: Int,
    val receivedAt: Long,
    val inferenceMillis: Long
)

@TransformExperimental
class VisionCameraSession(
    private val context: Context,
    private val owner: LifecycleOwner,
    val previewView: PreviewView,
    private val onFrame: (VisionFrame?) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onTorch: (Boolean, Boolean) -> Unit
) : AutoCloseable {
    private val main = ContextCompat.getMainExecutor(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val providerFuture = ProcessCameraProvider.getInstance(context)
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var detector: ObjectDetector? = null // confined to worker
    @Volatile private var closed = false
    @Volatile private var frozen = false
    @Volatile private var generation = 0
    private var torch = false
    private var lastFrameAt = 0L // confined to worker
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var displayRotation = -1
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (previewView.display?.displayId == displayId && previewView.display.rotation != displayRotation) bind()
        }
    }
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) bind()
    }
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> bind()
            Lifecycle.Event.ON_PAUSE -> stop()
            else -> Unit
        }
    }

    init {
        onStatus("Loading bundled EfficientDet-Lite0…")
        worker.execute {
            try {
                detector = ObjectDetector(context.assets)
            } catch (error: Exception) {
                reportDetectorFailure(error)
            } catch (error: LinkageError) {
                reportDetectorFailure(error)
            }
        }
        owner.lifecycle.addObserver(observer)
        previewView.addOnLayoutChangeListener(layoutListener)
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        providerFuture.addListener({
            if (!closed) {
                try { provider = providerFuture.get(); bind() }
                catch (error: Exception) { onStatus("Camera unavailable: ${error.message}") }
            }
        }, main)
    }

    private fun reportDetectorFailure(error: Throwable) {
        main.execute {
            if (!closed) onStatus("Object detector unavailable: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun bind() {
        if (closed || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val p = provider ?: return
        val viewport = previewView.viewPort ?: return // wait for layout
        stop()
        try {
            if (!p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                onStatus("No rear camera is available on this device")
                return
            }
            displayRotation = previewView.display.rotation
            val currentGeneration = generation
            val newPreview = Preview.Builder().setTargetRotation(displayRotation).build()
            val newAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(displayRotation)
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            preview = newPreview
            analysis = newAnalysis
            newPreview.setSurfaceProvider(previewView.surfaceProvider)
            newAnalysis.setAnalyzer(worker) { image -> analyze(image, currentGeneration) }
            camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA,
                UseCaseGroup.Builder().setViewPort(viewport).addUseCase(newPreview).addUseCase(newAnalysis).build())
            onTorch(camera!!.cameraInfo.hasFlashUnit(), false)
        } catch (error: Exception) {
            stop()
            onStatus("Camera could not start: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun analyze(image: ImageProxy, session: Int) {
        var upright: Bitmap? = null
        try {
            if (closed || session != generation || frozen) return
            val model = detector ?: return
            val start = SystemClock.elapsedRealtime()
            if (start - lastFrameAt < 100) return
            lastFrameAt = start
            val transform = ImageProxyTransformFactory().apply {
                isUsingCropRect = true
                isUsingRotationDegrees = true
            }.getOutputTransform(image)
            upright = cropAndRotate(image)
            val detections = model.detect(upright)
            val bitmap = upright
            val inferenceTime = SystemClock.elapsedRealtime() - start
            main.execute {
                if (closed || session != generation || frozen) return@execute
                try {
                    val output = previewView.outputTransform ?: return@execute
                    val coordinateTransform = CoordinateTransform(transform, output)
                    val imageToView = Matrix().also { coordinateTransform.transform(it) }
                    val mapped = detections.map { detection ->
                        val b = detection.box
                        val rect = RectF(b.left, b.top, b.right, b.bottom)
                        coordinateTransform.mapRect(rect)
                        detection.copy(box = Box2(rect.left, rect.top, rect.right, rect.bottom))
                    }.filter { it.box.valid }
                    onFrame(VisionFrame(bitmap, mapped, imageToView, previewView.width, previewView.height, start, inferenceTime))
                    onStatus(if (mapped.isEmpty()) "No objects above score threshold 0.35" else "${mapped.size} object region(s) • ${inferenceTime} ms inference")
                } catch (error: Exception) {
                    onFrame(null)
                    onStatus("Overlay transform unavailable: ${error.message}")
                }
            }
            upright = null // delivered bitmap is immutable and owned by UI; GC reclaims old frames
        } catch (error: Exception) {
            reportAnalysisFailure(session, error)
        } catch (error: LinkageError) {
            reportAnalysisFailure(session, error)
        } finally {
            upright?.recycle()
            image.close() // every skip, exception, conversion and inference path
        }
    }

    private fun reportAnalysisFailure(session: Int, error: Throwable) {
        main.execute {
            if (!closed && session == generation) {
                onFrame(null)
                onStatus("Analysis unavailable: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun cropAndRotate(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        try {
            val crop = image.cropRect
            val degrees = image.imageInfo.rotationDegrees
            val w = crop.width(); val h = crop.height()
            val rotated = Bitmap.createBitmap(if (degrees % 180 == 0) w else h, if (degrees % 180 == 0) h else w, Bitmap.Config.ARGB_8888)
            val corners = listOf(Point2(0f, 0f), Point2(w.toFloat(), 0f), Point2(0f, h.toFloat()))
            val from = corners.flatMap { listOf(it.x + crop.left, it.y + crop.top) }.toFloatArray()
            val to = corners.map { rotatePoint(it, w, h, degrees) }.flatMap { listOf(it.x, it.y) }.toFloatArray()
            val matrix = Matrix().apply { check(setPolyToPoly(from, 0, to, 0, 3)) }
            Canvas(rotated).drawBitmap(raw, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            return rotated
        } finally { raw.recycle() }
    }

    fun setFrozen(value: Boolean) { frozen = value }
    fun toggleTorch() {
        val c = camera ?: return
        if (!c.cameraInfo.hasFlashUnit()) return
        val desired = !torch
        val result = c.cameraControl.enableTorch(desired)
        result.addListener({
            if (!closed && camera === c) {
                try { result.get(); torch = desired; onTorch(true, torch) }
                catch (_: Exception) { onStatus("Torch could not be changed") }
            }
        }, main)
    }

    private fun stop() {
        generation++
        frozen = false
        analysis?.clearAnalyzer()
        val useCases = listOfNotNull(preview, analysis)
        if (useCases.isNotEmpty()) provider?.unbind(*useCases.toTypedArray())
        analysis = null; preview = null; camera = null; torch = false
        onFrame(null)
        onTorch(false, false)
    }
    override fun close() {
        if (closed) return
        closed = true
        stop()
        owner.lifecycle.removeObserver(observer)
        previewView.removeOnLayoutChangeListener(layoutListener)
        displayManager.unregisterDisplayListener(displayListener)
        worker.execute { detector?.close(); detector = null }
        worker.shutdown()
    }
}
