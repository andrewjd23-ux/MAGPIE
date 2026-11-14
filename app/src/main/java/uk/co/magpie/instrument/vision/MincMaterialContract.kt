package uk.co.magpie.instrument.vision

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Exact trained label order from the pinned source config; not COCO object labels. */
object MincMaterialContract {
    const val MODEL_ID = "MINC-Materials-23-INT8"
    const val MODEL_SHA256 = "0222f661abc762bc0ef69a7eded939a921ab7e0f2174c37788891162f1d0f2c8"
    const val MODEL_BYTES = 88864018L
    val labels = listOf("brick", "carpet", "ceramic", "fabric", "foliage", "food", "glass", "hair", "leather",
        "metal", "mirror", "other", "painted", "paper", "plastic", "polishedstone", "skin", "sky", "stone", "tile", "wallpaper", "water", "wood")
    val families = labels.associateWith { label ->
        when (label) {
            "metal" -> MaterialFamily.METAL
            // These labels do not establish the underlying object's composition.
            "mirror", "other", "painted", "sky" -> MaterialFamily.UNSPECIFIED
            else -> MaterialFamily.NONMETAL
        }
    }
    fun predictions(logits: FloatArray): MaterialResult.Predictions {
        require(logits.size == labels.size && logits.all { it.isFinite() }) { "Invalid material logits" }
        val maxLogit = logits.max().toDouble()
        val exponentials = logits.map { exp(it.toDouble() - maxLogit) }
        val sum = exponentials.sum()
        require(sum.isFinite() && sum > 0)
        return MaterialResult.Predictions(MODEL_ID, labels.indices.map { MaterialScore(labels[it], (exponentials[it] / sum).toFloat()) }, calibrationValidated = false)
    }
}

/** RGB → antialiased bilinear 224×224 → [-1,1] FLOAT32 NCHW. No image/object semantics. */
object MincPreprocessor {
    const val SIZE = 224
    private const val PRECISION = 22
    private const val ONE = 1 shl PRECISION
    private data class Kernel(val start: Int, val weights: IntArray)

    private fun kernels(source: Int): Array<Kernel> {
        val scale = source.toDouble() / SIZE
        val support = max(1.0, scale)
        return Array(SIZE) { out ->
            val center = (out + .5) * scale
            val first = max(0, floor(center - support + .5).toInt())
            val end = min(source, floor(center + support + .5).toInt())
            val weights = DoubleArray(end - first) { j -> max(0.0, 1.0 - kotlin.math.abs((first + j - center + .5) / support)) }
            val total = weights.sum()
            Kernel(first, IntArray(weights.size) { floor(weights[it] / total * ONE + .5).toInt() })
        }
    }
    private fun channel(sum: Long) = ((sum + (ONE shr 1)) shr PRECISION).toInt().coerceIn(0, 255)

    /** Separable triangular resampling with 8-bit rounding, checked against Pillow reference. */
    fun resizeRgb(input: MaterialInput): IntArray {
        val horizontal = kernels(input.width)
        val vertical = kernels(input.height)
        val intermediate = IntArray(SIZE * input.height)
        for (y in 0 until input.height) for (x in 0 until SIZE) {
            val k = horizontal[x]
            var r = 0L; var g = 0L; var b = 0L
            k.weights.forEachIndexed { offset, weight ->
                val pixel = input.argb[y * input.width + k.start + offset]
                r += (pixel shr 16 and 255).toLong() * weight
                g += (pixel shr 8 and 255).toLong() * weight
                b += (pixel and 255).toLong() * weight
            }
            intermediate[y * SIZE + x] = (255 shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
        val resized = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val k = vertical[y]
            var r = 0L; var g = 0L; var b = 0L
            k.weights.forEachIndexed { offset, weight ->
                val pixel = intermediate[(k.start + offset) * SIZE + x]
                r += (pixel shr 16 and 255).toLong() * weight
                g += (pixel shr 8 and 255).toLong() * weight
                b += (pixel and 255).toLong() * weight
            }
            resized[y * SIZE + x] = (255 shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
        return resized
    }
    fun tensor(input: MaterialInput): FloatArray {
        val rgb = resizeRgb(input)
        val area = SIZE * SIZE
        return FloatArray(area * 3).also { result ->
            rgb.forEachIndexed { i, pixel ->
                result[i] = ((pixel shr 16 and 255).toFloat() / 255f - .5f) / .5f
                result[area + i] = ((pixel shr 8 and 255).toFloat() / 255f - .5f) / .5f
                result[2 * area + i] = ((pixel and 255).toFloat() / 255f - .5f) / .5f
            }
        }
    }
}
