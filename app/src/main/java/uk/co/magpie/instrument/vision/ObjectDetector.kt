package uk.co.magpie.instrument.vision

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Own and invoke only on the camera analysis executor. CPU-only, no downloaded models. */
class ObjectDetector(assets: AssetManager) : AutoCloseable {
    private val labels = assets.open("models/coco_labels.txt").bufferedReader().use { it.readLines() }
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(320 * 320 * 3).order(ByteOrder.nativeOrder())
    private val inputBitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(320 * 320)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxes = Array(1) { Array(25) { FloatArray(4) } }
    private val classes = Array(1) { FloatArray(25) }
    private val scores = Array(1) { FloatArray(25) }
    private val count = FloatArray(1)

    init {
        val model = assets.openFd("models/efficientdet_lite0.tflite").use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
        try {
            require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 4)
            require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 320, 320, 3)))
            require(interpreter.getInputTensor(0).dataType() == DataType.UINT8)
            val expected = arrayOf(intArrayOf(1, 25, 4), intArrayOf(1, 25), intArrayOf(1, 25), intArrayOf(1))
            expected.forEachIndexed { i, shape ->
                require(interpreter.getOutputTensor(i).shape().contentEquals(shape))
                require(interpreter.getOutputTensor(i).dataType() == DataType.FLOAT32)
            }
            require(labels.size == 90 && labels[16] == "cat")
        } catch (error: Exception) { interpreter.close(); throw error }
    }

    fun detect(upright: Bitmap): List<Detection> {
        val letterbox = Letterbox(upright.width, upright.height)
        Canvas(inputBitmap).apply {
            drawColor(Color.rgb(127, 127, 127))
            drawBitmap(upright, null, RectF(letterbox.offsetX, letterbox.offsetY,
                320 - letterbox.offsetX, 320 - letterbox.offsetY), paint)
        }
        inputBitmap.getPixels(pixels, 0, 320, 0, 0, 320, 320)
        input.rewind()
        pixels.forEach { pixel ->
            input.put((pixel shr 16 and 255).toByte())
            input.put((pixel shr 8 and 255).toByte())
            input.put((pixel and 255).toByte())
        }
        input.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf(input), mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count))
        check(count[0].isFinite()) { "Invalid detector output" }
        return (0 until count[0].toInt().coerceIn(0, 25)).mapNotNull { i ->
            val score = scores[0][i]
            val classIndex = classes[0][i]
            if (!classIndex.isFinite() || classIndex % 1f != 0f) return@mapNotNull null
            val label = labels.getOrNull(classIndex.toInt()) ?: return@mapNotNull null
            if (!score.isFinite() || score < 0.35f || score > 1f || label == "n/a") return@mapNotNull null
            val b = boxes[0][i]
            val box = letterbox.fromModel(Box2(b[1], b[0], b[3], b[2])) ?: return@mapNotNull null
            Detection(label, score, box)
        }.take(10)
    }
    override fun close() { interpreter.close(); inputBitmap.recycle() }
}
