package com.openminis.app.ctx

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The C01–C32 table once more, under Robolectric NATIVE graphics with the
 * PRODUCTION codec (AndroidImageCodec → BitmapFactory / Bitmap backed by
 * Robolectric's Skia build) instead of the JDK codec CtxScenarioTest plugs in.
 *
 * Purpose: cross-check the JVM characterization against an Android graphics
 * implementation. Robolectric is NOT a device (see the probe:
 * no HEIC decode); a scenario that agrees here
 * agrees with Robolectric, not necessarily with a phone.
 *
 * [EXPECTED_DIFFERENT] lists the scenarios whose "current" assertions are
 * known not to hold under Robolectric, with the reason; every other scenario
 * must pass its current assertions unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoboScenarioTest {

    private val EXPECTED_DIFFERENT: Map<String, String> = mapOf()

    @Test fun `every scenario under Robolectric native graphics with the production codec`() {
        val outcome = linkedMapOf<String, String>()
        for (sc in CtxScenarios.all) {
            outcome[sc.id] = try {
                CtxSession("robo-" + sc.id.lowercase(), sc.window).use { s ->
                    sc.build(s)
                    val ctx = s.request()
                    val live = CtxPhase("live", ctx, CtxProvider.entries.associateWith { CtxSession.wire(it, ctx.messages) }, s.history.toList())
                    val run0 = CtxRun(sc, s, live, null)
                    sc.beforeRestart(s, run0)
                    val restarted = sc.restart?.let {
                        it(s)
                        val c2 = s.request()
                        CtxPhase("restart", c2, CtxProvider.entries.associateWith { p -> CtxSession.wire(p, c2.messages) }, s.history.toList())
                    }
                    val run = CtxRun(sc, s, live, restarted).also { it.facts.putAll(run0.facts) }
                    sc.current(run)
                    "agrees"
                }
            } catch (e: Throwable) {
                "differs: " + (e.message ?: e.javaClass.simpleName).lines().first().take(200)
            }
            println("[ROBO-SCENARIO] ${sc.id} ${outcome[sc.id]}")
        }
        val differing = outcome.filterValues { it != "agrees" }.keys
        assertEquals("scenarios whose JVM characterization does not hold under Robolectric", EXPECTED_DIFFERENT.keys, differing)
    }

    /**
     * C09's re-encoded transparent image, decoded back: the transparent half is
     * black (premultiplied ARGB → JPEG drops alpha onto 0,0,0). The JDK codec
     * cannot be evidence for this; Robolectric's Skia shows it.
     */
    @Test fun `C09 a transparent image re-encoded for size comes out black where it was transparent`() {
        val sc = CtxScenarios.all.single { it.id == "C09" }
        CtxSession("robo-c09bg", sc.window).use { s ->
            sc.build(s)
            val imgs = com.openminis.app.imagerepro.ImageRealFixtures.wireImages(CtxSession.wire(CtxProvider.ANTHROPIC, s.request().messages))
            val jpeg = imgs[2].bytes
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            val transparentSide = bmp.getPixel(bmp.width / 8, bmp.height / 2)
            val opaqueSideAlpha = android.graphics.Color.alpha(bmp.getPixel(bmp.width * 7 / 8, bmp.height / 2))
            println("[ROBO-C09] ${bmp.width}x${bmp.height} transparent side px=%08x opaque side alpha=$opaqueSideAlpha".format(transparentSide))
            assertEquals("image/jpeg", imgs[2].mime)
            assertEquals("the transparent half is black", 0xFF000000.toInt(), transparentSide)
            // The 512 px one stays a PNG with its alpha.
            val png = imgs[0].bytes
            val p = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
            assertEquals("the PNG keeps transparency", 0, android.graphics.Color.alpha(p.getPixel(p.width / 8, p.height / 2)))
        }
    }
}
