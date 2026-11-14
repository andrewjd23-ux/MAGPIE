package uk.co.magpie.instrument.vision

import org.junit.Assert.*
import org.junit.Test

class TargetSelectionTest {
    private val large = Detection("cup", .9f, Box2(0f, 0f, 100f, 100f))
    private val small = Detection("cup", .7f, Box2(20f, 20f, 50f, 50f))
    @Test fun selectsSpecificSmallestOverlappingDetectionAndDoesNotReleaseOnMiss() {
        assertEquals(small, selectDetection(Point2(25f, 25f), listOf(large, small)))
        val selection = TargetSelection()
        val token = selection.acquire(small)
        assertNull(selectDetection(Point2(200f, 200f), listOf(large, small)))
        assertEquals(small, selection.detection)
        assertTrue(selection.accepts(token))
    }
    @Test fun laterFramesDoNotChangeAcquiredTargetOrAcceptAnOldAnalysis() {
        val selection = TargetSelection()
        val old = selection.acquire(small)
        // A later same-category detection is not a tracking update.
        val next = small.copy(box = Box2(60f, 60f, 90f, 90f))
        assertEquals(small, selection.detection)
        val current = selection.acquire(next)
        assertFalse(selection.accepts(old))
        assertTrue(selection.accepts(current))
        selection.release()
        assertFalse(selection.accepts(current))
        assertNull(selection.detection)
    }
    @Test fun cropInvertsScaleAndPreviewCenterCropOffset() {
        // 640×480 image scales 2×, cropping 160 preview pixels at either side.
        val transform = Affine2(2f, 0f, -160f, 0f, 2f, 0f)
        val source = Box2(100f, 80f, 300f, 200f)
        assertEquals(PixelCrop(100, 80, 300, 200), imageCrop(transform.map(source), transform, 640, 480))
    }
    @Test fun cropInvertsEveryRotationAndMirroring() {
        val source = Box2(10f, 20f, 100f, 120f)
        val transforms = listOf(
            Affine2(1f, 0f, 0f, 0f, 1f, 0f),
            Affine2(0f, -2f, 960f, 2f, 0f, -100f),
            Affine2(-2f, 0f, 1280f, 0f, -2f, 960f),
            Affine2(0f, 2f, -10f, -2f, 0f, 1280f),
            Affine2(-1f, 0f, 640f, 0f, 1f, 0f)
        )
        transforms.forEach { assertEquals(PixelCrop(10, 20, 100, 120), imageCrop(it.map(source), it, 640, 480)) }
    }
    @Test fun cropClipsAndRejectsInvalidOrInvisibleRegions() {
        val identity = Affine2(1f, 0f, 0f, 0f, 1f, 0f)
        assertEquals(PixelCrop(0, 0, 11, 21), imageCrop(Box2(-1f, -3f, 10.2f, 20.1f), identity, 100, 100))
        assertNull(imageCrop(Box2(110f, 110f, 120f, 120f), identity, 100, 100))
        assertNull(imageCrop(Box2(1f, 1f, 10f, 10f), Affine2(0f, 0f, 0f, 0f, 0f, 0f), 100, 100))
        assertNull(imageCrop(Box2(1f, 1f, 10f, 10f), identity.copy(a = Float.NaN), 100, 100))
    }
}
