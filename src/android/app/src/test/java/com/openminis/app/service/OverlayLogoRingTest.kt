package com.openminis.app.service

import com.openminis.app.ProductionSources
import com.openminis.app.ui.theme.DarkChatPalette
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-overlay-ring-outside] / [T-overlay-ring-track] / [T-overlay-logo-disc]
 * (#333) The capsule's spinner is a faint full-circle track with a short bright
 * arc, drawn outside the logo circle and touching it, in one style for every
 * logo; a Soul image / emoji sits on one opaque neutral disc, filling the
 * circle; a night-mode switch reloads the logo; the ring is as far from the
 * capsule's left edge as from its top and bottom ([T-overlay-ring-even-margins]).
 */
class OverlayLogoRingTest {

    private val src = ProductionSources.read("service/ToolOverlayController.kt")

    // ── Geometry: outside the logo, no gap ──────────────────────────────────

    @Test
    fun `the stroke's inner edge is the logo's edge and the whole stroke lies outside it, at every density`() {
        for (density in listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)) {
            val logo = Math.round(26 * density)
            val stroke = RING_STROKE_DP_VALUE * density
            val inner = OverlayRingGeometry.arcInnerRadiusPx(logo, 0f, stroke)
            val outer = OverlayRingGeometry.arcOuterRadiusPx(logo, 0f, stroke)
            assertEquals("d=$density no gap", logo / 2f, inner, 0.001f)
            assertEquals("d=$density outer edge = logo radius + stroke", logo / 2f + stroke, outer, 0.001f)
            assertEquals(logo / 2f + stroke / 2f, OverlayRingGeometry.arcCentreRadiusPx(logo, 0f, stroke), 0.001f)
            // The view holds the whole stroke, with less than a pixel to spare per side.
            val box = OverlayRingGeometry.ringBoxPx(logo, 0f, stroke)
            assertTrue("d=$density box=$box clips the stroke", box / 2f >= outer - 0.001f)
            assertTrue("d=$density box=$box too large", box / 2f < outer + 1f)
            assertEquals("d=$density box = logo + 2 x stroke, rounded up", logo + 2 * Math.ceil(stroke.toDouble()).toInt(), box)
        }
    }

    @Test
    fun `Pixel 4a numbers - 26dp logo at 2_75x, 1_5dp and 2dp strokes`() {
        // 1.5dp = 4.125px: 79.75 exact -> 72 + 2 x ceil(4.125) = 82.
        assertEquals(82, OverlayRingGeometry.ringBoxPx(72, 0f, 4.125f))
        assertEquals(38.0625f, OverlayRingGeometry.arcCentreRadiusPx(72, 0f, 4.125f), 0.001f)
        assertEquals(36f, OverlayRingGeometry.arcInnerRadiusPx(72, 0f, 4.125f), 0.001f)
        assertEquals(40.125f, OverlayRingGeometry.arcOuterRadiusPx(72, 0f, 4.125f), 0.001f)
        // 2dp = 5.5px: 83 exact -> 72 + 2 x ceil(5.5) = 84.
        assertEquals(84, OverlayRingGeometry.ringBoxPx(72, 0f, 5.5f))
        assertEquals(36f, OverlayRingGeometry.arcInnerRadiusPx(72, 0f, 5.5f), 0.001f)
        assertEquals(41.5f, OverlayRingGeometry.arcOuterRadiusPx(72, 0f, 5.5f), 0.001f)
    }

    @Test
    fun `the ring is sized outside the logo, with no gap, and may overhang into the capsule padding`() {
        assertTrue(src.contains("private const val RING_GAP_DP = 0f"))
        assertTrue(src.contains("private const val RING_STROKE_DP = RING_STROKE_DP_VALUE"))
        assertTrue("stroke stays thin", RING_STROKE_DP_VALUE in 1.5f..2f)
        assertFalse("the inside-the-logo inset is gone", src.contains("private const val RING_INSET_DP"))
        assertTrue(src.contains("val ringTotal = OverlayRingGeometry.ringBoxPx(logoSize, ringGapPx, ringStrokePx)"))
        assertTrue(src.contains("centreRadiusPx = OverlayRingGeometry.arcCentreRadiusPx(logoSize, ringGapPx, ringStrokePx),"))
        assertTrue(src.contains("            clipChildren = false\n        }"))
        assertTrue(src.contains("container.clipChildren = false"))
        assertTrue(src.contains("container.clipToPadding = false"))
    }

    // ── Track and arc ───────────────────────────────────────────────────────

    @Test
    fun `the track is the arc's own blue at a lower alpha`() {
        assertTrue("spinner colour unchanged", src.contains("private val RING_COLOR = Color.argb(230, 120, 200, 255)"))
        assertTrue(src.contains("private val RING_TRACK_COLOR = Color.argb(RING_TRACK_ALPHA, 120, 200, 255)"))
        // Visible but clearly fainter than the arc (alpha 230).
        assertTrue("track alpha $RING_TRACK_ALPHA", RING_TRACK_ALPHA in 64..102)
    }

    @Test
    fun `a full-circle track is drawn first, then a short arc on the same circle`() {
        assertTrue("sweep $RING_SWEEP_DEG", RING_SWEEP_DEG in 90f..120f)
        val ring = src.substringAfter("private class RotatingRingView(").substringBefore("private class CloseGlyphView")
        val track = ring.indexOf("canvas.drawArc(rect, 0f, 360f, false, trackPaint)")
        val arc = ring.indexOf("canvas.drawArc(rect, -90f, RING_SWEEP_DEG, false, arcPaint)")
        assertTrue("full-circle track", track >= 0)
        assertTrue("short arc drawn over the track", arc > track)
        assertFalse("the 180-degree half circle is gone", ring.contains("180f"))
        assertTrue(ring.contains("rect.set(cx - centreRadiusPx, cy - centreRadiusPx, cx + centreRadiusPx, cy + centreRadiusPx)"))
        assertEquals("both paints share one stroke width", 2, Regex("strokeWidth = strokeWidthPx").findAll(ring).count())
    }

    @Test
    fun `one ring style for every logo - no per-logo or day-night arc colour`() {
        assertFalse(src.contains("OverlayRingColor"))
        assertFalse(src.contains("setRingColor"))
        assertTrue(src.contains("ringColor = RING_COLOR,\n            trackColor = RING_TRACK_COLOR,"))
    }

    // ── Even margins around the ring ────────────────────────────────────────

    @Test
    fun `the leading padding puts the ring as far from the left edge as from the top and bottom`() {
        assertTrue(src.contains("private const val CAPSULE_HEIGHT_DP = 46"))
        assertTrue(src.contains("private const val LOGO_SIZE_DP = 26"))
        // (46 - (26 + 2 x 1.5)) / 2 = 8.5dp above and below; + one stroke of overhang = 10dp.
        assertEquals(8.5f, OverlayMargins.ringToTopDp(46f, 26f, RING_STROKE_DP_VALUE), 0.001f)
        assertEquals(10f, OverlayMargins.startPaddingDp(46f, 26f, RING_STROKE_DP_VALUE), 0.001f)
        assertTrue(src.contains("private const val CAPSULE_PADDING_START_DP = 10"))
        assertTrue("the capsule's left padding uses the constant; top, right and bottom are unchanged",
            src.contains("setPadding(dpToPx(CAPSULE_PADDING_START_DP), dpToPx(6), dpToPx(14), dpToPx(6))"))
    }

    @Test
    fun `left, top and bottom gaps agree to within a pixel of rounding, at every density`() {
        fun dpToPx(dp: Int, density: Float) = (dp * density + 0.5f).toInt()
        for (density in listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)) {
            val stroke = RING_STROKE_DP_VALUE * density
            val left = OverlayMargins.ringToStartPx(dpToPx(10, density), stroke)
            val (top, bottom) = OverlayMargins.ringToTopBottomPx(dpToPx(46, density), dpToPx(26, density), stroke)
            assertTrue("d=$density left=$left top=$top", Math.abs(left - top) <= 1.001f)
            assertTrue("d=$density left=$left bottom=$bottom", Math.abs(left - bottom) <= 1.001f)
            assertTrue("d=$density ring inside the capsule", left > 0f && top > 0f)
            // The old 6dp padding was 4dp (one stroke short of half the difference) tighter.
            assertTrue("d=$density", top - OverlayMargins.ringToStartPx(dpToPx(6, density), stroke) > 3f * density)
        }
        // Pixel 4a: padding 28px, capsule 127px, logo 72px, stroke 4.125px.
        assertEquals(23.875f, OverlayMargins.ringToStartPx(28, 4.125f), 0.001f)
        assertEquals(22.875f to 23.875f, OverlayMargins.ringToTopBottomPx(127, 72, 4.125f))
    }

    @Test
    fun `the text width cap follows the leading padding`() {
        assertTrue(src.contains("private const val CAPSULE_NON_TEXT_DP = 70"))
        assertTrue(src.contains(
            "val maxW = (fixedCapsuleWidthPx() - dpToPx(CAPSULE_PADDING_START_DP + CAPSULE_NON_TEXT_DP)).coerceAtLeast(dpToPx(80))"))
        assertFalse("the hard-coded 76dp is gone", src.contains("dpToPx(76)"))
        // 26 logo + 6 its margin + 14 close + 10 its margin + 14 right padding.
        assertTrue(src.contains("rightMargin = dpToPx(6)"))
        assertTrue(src.contains("val s = dpToPx(14)"))
        assertTrue(src.contains("leftMargin = dpToPx(10)"))
        assertEquals(70, 26 + 6 + 14 + 10 + 14)
    }

    // ── Night-mode switch ───────────────────────────────────────────────────

    @Test
    fun `a night-mode switch reloads the logo without waiting for a state update`() {
        val cfg = src.substringAfter("fun onConfigurationChanged() {").substringBefore("val params = layoutParams")
        assertTrue(cfg.contains("if (isNightNow() != logoAppliedNight) applyLogoFor(lastLogoToolName)"))
        val logoFor = src.substringAfter("private fun applyLogoFor(toolName: String?) {").substringBefore("private fun applyPlate(")
        assertTrue("night is sampled with the logo, from the same resources",
            logoFor.contains("lastLogoToolName = toolName\n        logoAppliedNight = isNightNow()"))
    }

    // ── Identity disc ───────────────────────────────────────────────────────

    @Test
    fun `soul image and emoji sit on the opaque neutral disc, the image filling the circle`() {
        assertEquals("one step above the #1C1C1E capsule", Color(0xFF2C2C2E), DarkChatPalette.sheetHeaderBg)
        assertEquals("opaque", 1f, DarkChatPalette.sheetHeaderBg.alpha)
        assertTrue(src.contains("private val LOGO_DISC_COLOR = com.openminis.app.ui.theme.DarkChatPalette.sheetHeaderBg.toArgb()"))
        val soul = src.substringAfter("private fun applySoulIdentityIcon(iv: ImageView) {").substringBefore("private fun applyLauncherMark(")
        val image = soul.substringAfter("SoulIcon.decode(icon)?.let { bmp ->").substringBefore("return")
        assertTrue(image.contains("iv.scaleType = ImageView.ScaleType.CENTER_CROP"))
        assertTrue(image.contains("iv.setPadding(0, 0, 0, 0)"))
        assertTrue(image.contains("applyPlate(LOGO_DISC_COLOR)"))
        val emoji = soul.substringAfter("emojiBitmap(icon, px)?.let {").substringBefore("return")
        assertTrue(emoji.contains("applyPlate(LOGO_DISC_COLOR)"))
        assertFalse("the image-average plate is gone", src.contains("dominantPlateColor"))
    }

    @Test
    fun `tool glyphs keep their accent-tinted plate`() {
        val tool = src.substringAfter("private fun applyLogoFor(toolName: String?) {").substringBefore("private fun applyPlate(")
        assertTrue(tool.contains("applyPlate(Color.argb(PLATE_ALPHA, Color.red(accent), Color.green(accent), Color.blue(accent)))"))
    }
}
