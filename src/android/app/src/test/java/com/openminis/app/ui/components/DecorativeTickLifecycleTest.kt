package com.openminis.app.ui.components

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FIX-261025-14] A reader in a stopped screen no longer keeps the decorative clock ticking. */
class DecorativeTickLifecycleTest {
    @Test fun `a reader holds the clock only between ON_START and ON_STOP`() {
        val src = ProductionSources.read("ui/components/DecorativeAnimation.kt")
        val body = src.substringAfter("fun rememberDecorativeTick(): State<Long> {").substringBefore("return DecorativeClock.nanos")
        assertTrue(body.contains("Lifecycle.Event.ON_START -> hold()"))
        assertTrue(body.contains("Lifecycle.Event.ON_STOP -> drop()"))
        assertTrue("released when it leaves composition", body.contains("removeObserver(observer)") && body.contains("drop()\n        }"))
        assertFalse("never acquired unconditionally", Regex("DisposableEffect\\(Unit\\) \\{\\s+DecorativeClock.acquire\\(\\)").containsMatchIn(body))
    }
}
