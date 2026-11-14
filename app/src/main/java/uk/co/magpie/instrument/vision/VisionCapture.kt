package uk.co.magpie.instrument.vision

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.MediaStore
import java.time.Instant
import java.util.Locale
import kotlin.math.max

private val amber = Color.rgb(212, 171, 98)
private val green = Color.rgb(166, 198, 160)

/** Shared by the live overlay, frozen analyzed frame and exported screenshot. */
fun drawVisionOverlay(canvas: Canvas, frame: VisionFrame, selected: Detection?, dimOthers: Boolean = selected != null) {
    val textSize = max(14f, frame.viewWidth / 28f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    frame.detections.forEach { detection ->
        val b = detection.box
        val isSelected = detection === selected
        val dimmed = dimOthers && !isSelected
        paint.color = if (dimmed) Color.argb(80, 166, 198, 160) else amber
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (isSelected) 6f else 3f
        canvas.drawRect(b.left, b.top, b.right, b.bottom, paint)
        val label = "${detection.label} • score ${String.format(Locale.UK, "%.2f", detection.score)}"
        paint.style = Paint.Style.FILL
        paint.textSize = textSize
        val x = b.left.coerceIn(0f, max(0f, frame.viewWidth - paint.measureText(label)))
        val baseline = (b.top - 8f).coerceIn(textSize + 5f, max(textSize + 5f, frame.viewHeight - 5f))
        val labelColor = paint.color
        paint.color = Color.argb(if (dimmed) 70 else 220, 14, 21, 18)
        canvas.drawRect(x, baseline - textSize - 4f, x + paint.measureText(label) + 5f, baseline + 4f, paint)
        paint.color = labelColor
        canvas.drawText(label, x, baseline, paint)
    }
}

fun drawAnalyzedFrame(canvas: Canvas, frame: VisionFrame) {
    canvas.drawColor(Color.rgb(14, 21, 18))
    canvas.drawBitmap(frame.bitmap, frame.imageToView, Paint(Paint.FILTER_BITMAP_FLAG))
}

/** Saves the exact analyzed image with its boxes, not a different live preview frame. API 29+. */
fun saveVisionCapture(context: Context, frame: VisionFrame, selected: Detection?, magneticText: List<String>,
    analysisText: List<String> = listOf("Material unknown. No controlled scan; no object attribution.")): String {
    val textSize = max(16f, frame.viewWidth / 30f)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize; typeface = Typeface.MONOSPACE }
    val lines = (listOf("MAGPIE VISION • ${Instant.now()}", "Model scores are uncalibrated.",
        "Image is a snapshot; magnetic values at capture.") + magneticText + analysisText).flatMap { line ->
        buildList {
            var remainder = line
            while (remainder.isNotEmpty()) {
                val count = textPaint.breakText(remainder, true, (frame.viewWidth - 24f).coerceAtLeast(1f), null).coerceAtLeast(1)
                add(remainder.take(count)); remainder = remainder.drop(count)
            }
        }
    }
    val footerHeight = ((lines.size + 1) * textSize * 1.6f).toInt()
    val bitmap = Bitmap.createBitmap(frame.viewWidth, frame.viewHeight + footerHeight, Bitmap.Config.ARGB_8888)
    val resolver = context.contentResolver
    var uri: android.net.Uri? = null
    try {
        val canvas = Canvas(bitmap)
        drawAnalyzedFrame(canvas, frame)
        canvas.save()
        canvas.clipRect(0, 0, frame.viewWidth, frame.viewHeight)
        drawVisionOverlay(canvas, frame, selected)
        canvas.restore()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(14, 21, 18) }
        canvas.drawRect(0f, frame.viewHeight.toFloat(), frame.viewWidth.toFloat(), bitmap.height.toFloat(), paint)
        paint.color = green; paint.textSize = textSize; paint.typeface = Typeface.MONOSPACE
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, 12f, frame.viewHeight + (index + 1) * textSize * 1.6f, paint)
        }
        val name = "MAGPIE-${System.currentTimeMillis()}.png"
        uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MAGPIE")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: error("Could not create screenshot")
        resolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ?: error("Could not write screenshot")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return "Saved $name in Pictures/MAGPIE"
    } catch (error: Exception) {
        uri?.let { resolver.delete(it, null, null) }
        throw error
    } finally { bitmap.recycle() }
}
