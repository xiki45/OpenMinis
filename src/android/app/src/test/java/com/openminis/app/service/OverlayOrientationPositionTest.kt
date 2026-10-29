package com.openminis.app.service

import android.content.Context
import com.openminis.app.ProductionSources
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.service.OverlayPosition.Avoid
import com.openminis.app.service.OverlayPosition.Orientation
import com.openminis.app.service.OverlayPosition.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [T-overlay-orientation-position] Alice Q2, reproduced on a Pixel 4a: the
 * capsule dragged to y=1796 in portrait was clamped to 946 in landscape, that
 * clamp was SAVED over the one stored position, and back in portrait it stayed
 * at 946. In landscape it also sat on the home gesture bar.
 *
 * Now each orientation keeps its own position, a configuration change only
 * clamps what is shown, only a drag writes (to the current orientation), the
 * single position earlier builds stored is read as the portrait one, and in
 * landscape the capsule stays clear of the navigation bar / gesture inset.
 *
 * [T-overlay-no-top-hide] In both orientations the shown capsule is fully
 * below the top inset (status bar / cutout), and never above the top edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayOrientationPositionTest {

    // Pixel 4a, the repro's numbers: capsule 538 x 127 in portrait, 660 wide
    // in landscape.
    private val portraitW = 1080
    private val portraitH = 2340
    private val landW = 2340
    private val landH = 1080
    private val ch = 127
    private val cwPortrait = 538
    private val cwLand = 660
    private val gestureBar = 63

    private class FakeStore : OverlayPosition.Store {
        val saved = mutableMapOf<Orientation, Point>()
        var writes = 0
        override fun get(orientation: Orientation): Point? = saved[orientation]
        override fun set(orientation: Orientation, point: Point) { saved[orientation] = point; writes++ }
    }

    private fun defaultFor(w: Int, h: Int) = Point(28, h - 300)

    private fun shownPortrait(store: FakeStore) = OverlayPosition.displayed(
        store, defaultFor(portraitW, portraitH), portraitW, portraitH, cwPortrait, ch, Avoid.NONE,
    )

    private fun shownLand(store: FakeStore, avoid: Avoid = Avoid(bottom = gestureBar)) = OverlayPosition.displayed(
        store, defaultFor(landW, landH), landW, landH, cwLand, ch, avoid,
    )

    @Test fun `orientation follows the longer side`() {
        assertEquals(Orientation.PORTRAIT, Orientation.of(portraitW, portraitH))
        assertEquals(Orientation.LANDSCAPE, Orientation.of(landW, landH))
        assertEquals("square counts as portrait", Orientation.PORTRAIT, Orientation.of(1000, 1000))
    }

    @Test fun `portrait to landscape and back restores the portrait position`() {
        val store = FakeStore()
        OverlayPosition.dragEnded(store, portraitW, portraitH, Point(512, 1796))
        assertEquals(Point(512, 1796), shownPortrait(store))
        val land = shownLand(store)
        assertTrue("landscape shows something on screen: $land", land.y + ch <= landH)
        assertEquals("rotating writes nothing", 1, store.writes)
        assertEquals(Point(512, 1796), shownPortrait(store))
    }

    @Test fun `a drag writes only the current orientation`() {
        val store = FakeStore()
        OverlayPosition.dragEnded(store, portraitW, portraitH, Point(512, 949))
        OverlayPosition.dragEnded(store, landW, landH, Point(1500, 300))
        assertEquals(Point(512, 949), store.saved[Orientation.PORTRAIT])
        assertEquals(Point(1500, 300), store.saved[Orientation.LANDSCAPE])
        assertEquals(Point(1500, 300), shownLand(store))
        assertEquals(Point(512, 949), shownPortrait(store))
    }

    @Test fun `landscape with nothing saved for it shows the default, not the portrait position`() {
        val store = FakeStore()
        OverlayPosition.dragEnded(store, portraitW, portraitH, Point(512, 1796))
        val d = defaultFor(landW, landH)
        assertEquals(OverlayPosition.clamp(d.x, d.y, landW, landH, cwLand, ch, Avoid(bottom = gestureBar)), shownLand(store))
    }

    @Test fun `in landscape the capsule stays above the gesture bar`() {
        val store = FakeStore()
        OverlayPosition.dragEnded(store, landW, landH, Point(800, 5000))
        val shown = shownLand(store)
        assertEquals("fully above the inset", landH - gestureBar - ch, shown.y)
        assertEquals("the saved drag is untouched", Point(800, 5000), store.saved[Orientation.LANDSCAPE])
        // A side navigation bar (three-button nav in landscape) is avoided too.
        assertEquals(landW - 130 - cwLand, OverlayPosition.clamp(5000, 300, landW, landH, cwLand, ch, Avoid(right = 130)).x)
        assertEquals(130, OverlayPosition.clamp(-5000, 300, landW, landH, cwLand, ch, Avoid(left = 130)).x)
    }

    // [T-overlay-no-top-hide] Was "the top edge still lets half the capsule
    // hide, as de5b07a53 decided" (expected y = -ch / 2). The owner withdrew
    // that: the top keeps the capsule fully on screen, in both orientations.
    @Test fun `the top edge keeps the whole capsule on screen`() {
        assertEquals(0, OverlayPosition.clamp(300, -5000, landW, landH, cwLand, ch, Avoid(bottom = gestureBar)).y)
        assertEquals(0, OverlayPosition.clamp(300, -5000, portraitW, portraitH, cwPortrait, ch).y)
        assertEquals("a small overhang too", 0, OverlayPosition.clamp(300, -1, portraitW, portraitH, cwPortrait, ch).y)
        assertEquals("0 itself is allowed", 0, OverlayPosition.clamp(300, 0, portraitW, portraitH, cwPortrait, ch).y)
    }

    // [T-overlay-no-top-hide] Was "...at every edge"; the top is now fully on
    // screen (y = 0, was -63). Left, right and bottom are unchanged.
    @Test fun `portrait keeps the old half-on-screen rule left, right and bottom`() {
        assertEquals(Point(-269, portraitH - 64), OverlayPosition.clamp(-5000, 5000, portraitW, portraitH, cwPortrait, ch))
        assertEquals(Point(portraitW - 269, 0), OverlayPosition.clamp(5000, -5000, portraitW, portraitH, cwPortrait, ch))
    }

    @Test fun `system bar avoidance applies in landscape only`() {
        assertEquals(Avoid.NONE, OverlayPosition.avoidFor(Orientation.PORTRAIT, left = 0, right = 0, bottom = 63))
        assertEquals(Avoid(left = 0, right = 130, bottom = 63), OverlayPosition.avoidFor(Orientation.LANDSCAPE, left = 0, right = 130, bottom = 63))
    }

    // ── Top edge [T-overlay-no-top-hide] ────────────────────────────────────

    // Pixel 4a status bar height; any positive inset behaves the same.
    private val statusBar = 135

    @Test fun `with a top inset the capsule stays fully below it`() {
        val top = Avoid(top = statusBar)
        for (y in listOf(-5000, -63, -1, 0, statusBar - 1)) {
            assertEquals("y=$y", statusBar, OverlayPosition.clamp(300, y, portraitW, portraitH, cwPortrait, ch, top).y)
        }
        assertEquals("below the inset is left alone", 400, OverlayPosition.clamp(300, 400, portraitW, portraitH, cwPortrait, ch, top).y)
        assertEquals("x is not affected", Point(-269, statusBar), OverlayPosition.clamp(-5000, -5000, portraitW, portraitH, cwPortrait, ch, top))
    }

    @Test fun `a negative inset is treated as none, never as room above the edge`() {
        assertEquals(0, OverlayPosition.clamp(300, -5000, portraitW, portraitH, cwPortrait, ch, Avoid(top = -40)).y)
    }

    @Test fun `avoidFor gives the top in both orientations and nothing else in portrait`() {
        assertEquals(Avoid(top = statusBar), OverlayPosition.avoidFor(Orientation.PORTRAIT, left = 10, right = 20, bottom = 63, top = statusBar))
        assertEquals(Avoid(left = 0, right = 130, bottom = 63, top = statusBar),
            OverlayPosition.avoidFor(Orientation.LANDSCAPE, left = 0, right = 130, bottom = 63, top = statusBar))
        assertEquals(Avoid(top = 0), OverlayPosition.avoidFor(Orientation.PORTRAIT, 0, 0, 0, top = -5))
    }

    @Test fun `portrait - a capsule saved half past the top is shown fully on screen`() {
        val store = FakeStore()
        OverlayPosition.dragEnded(store, portraitW, portraitH, Point(512, -40))
        val avoid = OverlayPosition.avoidFor(Orientation.PORTRAIT, 0, 0, 0, top = statusBar)
        val shown = OverlayPosition.displayed(store, defaultFor(portraitW, portraitH), portraitW, portraitH, cwPortrait, ch, avoid)
        assertEquals(Point(512, statusBar), shown)
        // With no inset known (0) the top edge itself is the limit.
        assertEquals(Point(512, 0), shownPortrait(store))
    }

    @Test fun `landscape - the top and the gesture bar are both kept clear`() {
        val store = FakeStore()
        OverlayPosition.dragEnded(store, landW, landH, Point(1500, -40))
        val avoid = OverlayPosition.avoidFor(Orientation.LANDSCAPE, 0, 0, gestureBar, top = statusBar)
        assertEquals(Point(1500, statusBar), shownLand(store, avoid))
        OverlayPosition.dragEnded(store, landW, landH, Point(1500, 5000))
        assertEquals("bottom unchanged", landH - gestureBar - ch, shownLand(store, avoid).y)
    }

    @Test fun `the top clamp is display only - rotating and coming back writes nothing`() {
        val store = FakeStore()
        OverlayPosition.dragEnded(store, portraitW, portraitH, Point(512, -40))
        OverlayPosition.dragEnded(store, landW, landH, Point(1500, -30))
        val portraitAvoid = OverlayPosition.avoidFor(Orientation.PORTRAIT, 0, 0, 0, top = statusBar)
        val landAvoid = OverlayPosition.avoidFor(Orientation.LANDSCAPE, 0, 0, gestureBar, top = statusBar)
        repeat(3) {
            OverlayPosition.displayed(store, defaultFor(portraitW, portraitH), portraitW, portraitH, cwPortrait, ch, portraitAvoid)
            shownLand(store, landAvoid)
        }
        assertEquals("only the two drags wrote", 2, store.writes)
        assertEquals(Point(512, -40), store.saved[Orientation.PORTRAIT])
        assertEquals(Point(1500, -30), store.saved[Orientation.LANDSCAPE])
    }

    @Test fun `the default position is not moved by the top inset`() {
        val store = FakeStore()
        val avoid = OverlayPosition.avoidFor(Orientation.PORTRAIT, 0, 0, 0, top = statusBar)
        val d = defaultFor(portraitW, portraitH)
        assertEquals(d, OverlayPosition.displayed(store, d, portraitW, portraitH, cwPortrait, ch, avoid))
    }

    @Test fun `the controller passes the status bar top in both orientations and on every API level`() {
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        val fn = src.substringAfter("private fun systemBarAvoidance(").substringBefore("private fun statusBarTop()")
        assertTrue(fn.contains("val top = statusBarTop()"))
        assertFalse("no early return without the top", fn.contains("return OverlayPosition.Avoid.NONE"))
        assertTrue(fn.contains("OverlayPosition.avoidFor(orientation, insets.left, insets.right, insets.bottom, top)"))
        val top = src.substringAfter("private fun statusBarTop(): Int {").substringBefore("private fun dpToPx(")
        assertTrue(top.contains("WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()"))
        assertTrue("API 26-29 fallback", top.contains("getIdentifier(\"status_bar_height\", \"dimen\", \"android\")"))
        assertFalse("the drag itself is still not clamped", src.substringAfter("MotionEvent.ACTION_MOVE -> {")
            .substringBefore("MotionEvent.ACTION_UP").contains("OverlayPosition.clamp("))
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    @Test fun `the single position earlier builds stored is read as the portrait one`() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = ctx.getSharedPreferences("background_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("backgroundOverlayX", 29).putInt("backgroundOverlayY", 1872).commit()
        val repo = BackgroundSettingsRepository(ctx)
        assertEquals(29 to 1872, repo.getOverlayPosition(landscape = false))
        assertNull("nothing saved for landscape yet", repo.getOverlayPosition(landscape = true))
    }

    @Test fun `the two orientations are stored apart`() {
        val ctx = RuntimeEnvironment.getApplication()
        val repo = BackgroundSettingsRepository(ctx)
        repo.setOverlayPosition(512, 1796, landscape = false)
        repo.setOverlayPosition(1500, -40, landscape = true)
        assertEquals(512 to 1796, repo.getOverlayPosition(landscape = false))
        assertEquals(1500 to -40, repo.getOverlayPosition(landscape = true))
        // An older build reading the same file still finds its position.
        val prefs = ctx.getSharedPreferences("background_settings", Context.MODE_PRIVATE)
        assertEquals(512, prefs.getInt("backgroundOverlayX", 0))
        assertEquals(1796, prefs.getInt("backgroundOverlayY", 0))
    }

    // ── Wiring ──────────────────────────────────────────────────────────────

    @Test fun `a configuration change never writes the saved position`() {
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        val cfg = src.substringAfter("fun onConfigurationChanged() {").substringBefore("private fun dpToPx(")
        assertFalse(cfg.contains("setOverlayPosition"))
        assertFalse(cfg.contains("dragEnded"))
        assertTrue(cfg.contains("OverlayPosition.displayed("))
        val drag = src.substringAfter("MotionEvent.ACTION_UP -> {").substringBefore("MotionEvent.ACTION_CANCEL")
        assertTrue(drag.contains("OverlayPosition.dragEnded(positionStore,"))
        val attach = src.substringAfter("private fun attach() {").substringBefore("private fun buildView()")
        assertTrue(attach.contains("OverlayPosition.displayed("))
    }
}
