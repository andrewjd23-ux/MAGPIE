package uk.co.magpie.instrument.vision

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun rotatesCroppedImageEdgesForEveryCameraOrientation() {
        val p = Point2(10f, 20f)
        assertEquals(p, rotatePoint(p, 640, 480, 0))
        assertEquals(Point2(460f, 10f), rotatePoint(p, 640, 480, 90))
        assertEquals(Point2(630f, 460f), rotatePoint(p, 640, 480, 180))
        assertEquals(Point2(20f, 630f), rotatePoint(p, 640, 480, 270))
        assertEquals(Point2(480f, 0f), rotatePoint(Point2(0f, 0f), 640, 480, 90))
    }
    @Test fun letterboxRoundTripAccountsForPaddingInPortraitAndLandscape() {
        for ((w, h) in listOf(640 to 480, 480 to 640, 1920 to 1080, 320 to 320)) {
            val mapping = Letterbox(w, h)
            val a = mapping.toModel(Point2(w * .1f, h * .2f))
            val b = mapping.toModel(Point2(w * .8f, h * .9f))
            val result = mapping.fromModel(Box2(a.x, a.y, b.x, b.y))!!
            assertEquals(w * .1f, result.left, .001f)
            assertEquals(h * .2f, result.top, .001f)
            assertEquals(w * .8f, result.right, .001f)
            assertEquals(h * .9f, result.bottom, .001f)
        }
    }
    @Test fun rejectsPaddingOnlyAndNonFiniteBoxes() {
        val landscape = Letterbox(640, 480)
        assertNull(landscape.fromModel(Box2(0f, 0f, .5f, .05f)))
        assertNull(landscape.fromModel(Box2(Float.NaN, 0f, 1f, 1f)))
        assertEquals(Box2(0f, 0f, 640f, 480f), landscape.fromModel(Box2(-1f, -1f, 2f, 2f)))
    }
    @Test fun selectionDoesNotJumpToAnotherRegionOrCategory() {
        val old = Detection("cup", .8f, Box2(10f, 10f, 50f, 50f))
        val overlapping = old.copy(score = .7f, box = Box2(12f, 12f, 52f, 52f))
        assertEquals(overlapping, matchSelection(old, listOf(overlapping)))
        assertNull(matchSelection(old, listOf(old.copy(label = "bottle"))))
        assertNull(matchSelection(old, listOf(old.copy(box = Box2(100f, 100f, 140f, 140f)))))
        assertNull(matchSelection(old, emptyList()))
    }
}
