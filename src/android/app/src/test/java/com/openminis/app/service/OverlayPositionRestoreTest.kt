package com.openminis.app.service

import android.content.Context
import com.openminis.app.ProductionSources
import com.openminis.app.data.repository.BackgroundSettingsRepository
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
 * [T-overlay-position-restore] The floating capsule comes back where the user
 * left it, including partly past the LEFT edge, where its window x is
 * negative. It used to be thrown back to the bottom-left default there,
 * because "nothing saved" was read as a negative value.
 *
 * [T-overlay-no-top-hide] The top edge no longer takes part: a negative y is
 * still a saved position (not "nothing saved"), but it is shown fully on
 * screen, y >= 0, instead of half hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayPositionRestoreTest {

    // Pixel 4a portrait: 1080 x 2340 at 2.75x, capsule 540 x 127.
    private val w = 1080
    private val h = 2340
    private val cw = 540
    private val ch = 127
    private val default = Point(28, 2065)

    private fun restore(saved: Point?, screenW: Int = w, screenH: Int = h) =
        OverlayPosition.restore(saved, default, screenW, screenH, cw, ch)

    // ── The reported bug ────────────────────────────────────────────────────

    @Test
    fun `a position dragged off the left edge is restored, like one dragged off the right edge`() {
        assertEquals(Point(800, 1500), restore(Point(800, 1500)))     // 260px past the right edge
        assertEquals(Point(-150, 1500), restore(Point(-150, 1500)))   // 150px past the left edge
        // [T-overlay-no-top-hide] A saved negative y is still restored, not
        // replaced by the default, but pulled down until the top is on screen.
        assertEquals("partly past the top edge: kept, top brought on screen", Point(300, 0), restore(Point(300, -40)))
        assertEquals("-1 is a position like any other", Point(-1, 0), restore(Point(-1, -1)))
    }

    @Test
    fun `only never dragged gets the default`() {
        assertEquals(default, restore(null))
        assertEquals(Point(0, 0), restore(Point(0, 0)))
        assertEquals("an ordinary on-screen position", Point(40, 1500), restore(Point(40, 1500)))
    }

    // ── Kept reachable ──────────────────────────────────────────────────────

    @Test
    fun `at least half of the capsule stays on screen left, right and bottom, and all of it at the top`() {
        assertEquals("far left", Point(-270, 1500), restore(Point(-5000, 1500)))
        assertEquals("far right", Point(810, 1500), restore(Point(5000, 1500)))
        assertEquals("far up: fully on screen [T-overlay-no-top-hide]", Point(300, 0), restore(Point(300, -5000)))
        assertEquals("far down", Point(300, 2276), restore(Point(300, 5000)))
        // Exactly half out is still left alone on the sides and the bottom...
        assertEquals(Point(-270, 2276), restore(Point(-270, 2276)))
        // ...but no longer at the top.
        assertEquals(Point(810, 0), restore(Point(810, -63)))
    }

    @Test
    fun `a position saved on a larger or rotated screen is pulled back until half shows`() {
        // Saved in landscape (2340 wide), restored in portrait.
        assertEquals(Point(810, 900), restore(Point(1700, 900)))
        // Saved in portrait near the bottom, restored in landscape (1080 high).
        assertEquals(Point(40, 1016), restore(Point(40, 2000), screenW = 2340, screenH = 1080))
        // A screen narrower than the capsule: the bounds stay ordered.
        assertEquals(Point(-70, 0), OverlayPosition.clamp(900, 0, 200, 2340, cw, ch))
        assertEquals(Point(-270, 0), OverlayPosition.clamp(-900, 0, 200, 2340, cw, ch))
    }

    @Test
    fun `rotation keeps a left-edge position instead of snapping it to zero`() {
        assertEquals(Point(-150, 500), OverlayPosition.clamp(-150, 500, 2340, 1080, cw, ch))
        assertEquals(Point(-150, 1016), OverlayPosition.clamp(-150, 1500, 2340, 1080, cw, ch))
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    @Test
    fun `never stored is null, and a stored negative position round-trips`() {
        val ctx = RuntimeEnvironment.getApplication()
        val repo = BackgroundSettingsRepository(ctx)
        assertNull(repo.getOverlayPosition())
        repo.setOverlayPosition(-150, 1500)
        assertEquals(-150 to 1500, repo.getOverlayPosition())
        repo.setOverlayPosition(-1, -1)
        assertEquals("what the old reader took for nothing saved", -1 to -1, repo.getOverlayPosition())
    }

    @Test
    fun `positions written by earlier builds are read as they are`() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = ctx.getSharedPreferences("background_settings", Context.MODE_PRIVATE)
        // An earlier build stored a left-edge drag with the same two int keys.
        prefs.edit().putInt("backgroundOverlayX", -213).putInt("backgroundOverlayY", 1730).commit()
        assertEquals(-213 to 1730, BackgroundSettingsRepository(ctx).getOverlayPosition())
        // Only one key present is not a position.
        prefs.edit().remove("backgroundOverlayY").commit()
        assertNull(BackgroundSettingsRepository(ctx).getOverlayPosition())
    }

    // ── Wiring ──────────────────────────────────────────────────────────────

    @Test
    fun `attach and the configuration change share the rule, and a drag is saved unclamped`() {
        val src = ProductionSources.read("service/ToolOverlayController.kt")
        val repo = ProductionSources.read("data/repository/BackgroundSettingsRepository.kt")
        val attach = src.substringAfter("private fun attach() {").substringBefore("private fun buildView()")
        // [T-overlay-orientation-position] Both read through the per-orientation store.
        assertTrue(attach.contains("OverlayPosition.displayed(\n                positionStore,"))
        assertFalse("no sign test on the saved position", attach.contains(">= 0"))
        val cfg = src.substringAfter("fun onConfigurationChanged() {").substringBefore("private fun dpToPx(")
        assertTrue(cfg.contains("OverlayPosition.displayed(\n                positionStore,"))
        assertFalse("no clamp to fully on-screen", cfg.contains("coerceIn(0,"))
        assertTrue("the drag itself is not restricted",
            src.contains("params.x = startParamX + dx\n                        params.y = startParamY + dy"))
        assertTrue(src.contains("OverlayPosition.dragEnded(positionStore, dm.widthPixels, dm.heightPixels, OverlayPosition.Point(params.x, params.y))"))
        assertTrue(repo.contains("if (!prefs.contains(kx) || !prefs.contains(ky)) return null"))
        assertFalse("the -1 sentinel is gone", repo.contains("prefs.getInt(kx, -1)"))
    }
}
