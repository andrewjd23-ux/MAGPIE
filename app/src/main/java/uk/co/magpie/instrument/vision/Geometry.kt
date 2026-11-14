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

/** Re-selection by overlapping region only; this is deliberately not an identity tracker. */
fun matchSelection(previous: Detection?, detections: List<Detection>): Detection? = previous?.let { old ->
    detections.filter { it.label == old.label && it.box.iou(old.box) >= 0.25f }
        .maxByOrNull { it.box.iou(old.box) }
}
