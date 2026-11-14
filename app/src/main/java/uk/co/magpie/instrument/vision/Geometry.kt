package uk.co.magpie.instrument.vision

import kotlin.math.max
import kotlin.math.min

data class Point2(val x: Float, val y: Float)
data class Box2(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val valid get() = listOf(left, top, right, bottom).all { it.isFinite() } && width > 0 && height > 0
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun iou(other: Box2): Float {
        val intersection = max(0f, min(right, other.right) - max(left, other.left)) *
            max(0f, min(bottom, other.bottom) - max(top, other.top))
        val union = width * height + other.width * other.height - intersection
        return if (valid && other.valid && union > 0) intersection / union else 0f
    }
}

/** Clockwise rotation after cropping; coordinates are edges, not pixel indices. */
fun rotatePoint(point: Point2, width: Int, height: Int, degrees: Int): Point2 = when (degrees) {
    0 -> point
    90 -> Point2(height - point.y, point.x)
    180 -> Point2(width - point.x, height - point.y)
    270 -> Point2(point.y, width - point.x)
    else -> error("Only orthogonal camera rotations are supported")
}

/** Exactly matches detector preprocessing. Model boxes use normalized [0,1] edges. */
class Letterbox(val width: Int, val height: Int, val size: Int = 320) {
    init { require(width > 0 && height > 0 && size > 0) }
    val scale = min(size.toFloat() / width, size.toFloat() / height)
    val offsetX = (size - width * scale) / 2f
    val offsetY = (size - height * scale) / 2f
    fun toModel(p: Point2) = Point2((p.x * scale + offsetX) / size, (p.y * scale + offsetY) / size)
    fun fromModel(box: Box2): Box2? {
        if (!box.valid) return null
        return Box2(
            ((box.left * size - offsetX) / scale).coerceIn(0f, width.toFloat()),
            ((box.top * size - offsetY) / scale).coerceIn(0f, height.toFloat()),
            ((box.right * size - offsetX) / scale).coerceIn(0f, width.toFloat()),
            ((box.bottom * size - offsetY) / scale).coerceIn(0f, height.toFloat())
        ).takeIf { it.valid }
    }
}

data class Detection(val label: String, val score: Float, val box: Box2)

/** Affine image-to-preview map. Inverting this map, not screen coordinates, defines crops. */
data class Affine2(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun map(p: Point2) = Point2(a * p.x + b * p.y + c, d * p.x + e * p.y + f)
    fun inverse(): Affine2? {
        if (!listOf(a, b, c, d, e, f).all { it.isFinite() }) return null
        val determinant = a * e - b * d
        if (!determinant.isFinite() || kotlin.math.abs(determinant) < 1e-8f) return null
        return Affine2(e / determinant, -b / determinant, (b * f - e * c) / determinant,
            -d / determinant, a / determinant, (d * c - a * f) / determinant)
    }
    fun map(box: Box2): Box2 {
        val corners = listOf(Point2(box.left, box.top), Point2(box.right, box.top),
            Point2(box.left, box.bottom), Point2(box.right, box.bottom)).map(::map)
        return Box2(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }
}

data class PixelCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

fun imageCrop(previewBox: Box2, imageToPreview: Affine2, imageWidth: Int, imageHeight: Int): PixelCrop? {
    if (!previewBox.valid || imageWidth <= 0 || imageHeight <= 0) return null
    val source = imageToPreview.inverse()?.map(previewBox)?.takeIf { it.valid } ?: return null
    val crop = PixelCrop(
        kotlin.math.floor(source.left.toDouble()).toInt().coerceIn(0, imageWidth),
        kotlin.math.floor(source.top.toDouble()).toInt().coerceIn(0, imageHeight),
        kotlin.math.ceil(source.right.toDouble()).toInt().coerceIn(0, imageWidth),
        kotlin.math.ceil(source.bottom.toDouble()).toInt().coerceIn(0, imageHeight)
    )
    return crop.takeIf { it.width > 0 && it.height > 0 }
}

/** A miss does not release a target. No matching against later frames is performed. */
fun selectDetection(point: Point2, detections: List<Detection>): Detection? = detections
    .filter { it.box.valid && it.box.contains(point.x, point.y) }
    .sortedWith(compareBy<Detection> { it.box.width * it.box.height }.thenByDescending { it.score })
    .firstOrNull()

/** Token guard prevents a previous asynchronous crop/classification overwriting a new target. */
class TargetSelection {
    @Volatile var token: Long = 0; private set
    var detection: Detection? = null; private set
    fun acquire(detection: Detection): Long { this.detection = detection; return ++token }
    fun release() { detection = null; token++ }
    fun accepts(token: Long) = token == this.token && detection != null
}
