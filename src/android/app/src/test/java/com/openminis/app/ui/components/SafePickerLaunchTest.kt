package com.openminis.app.ui.components

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-android-picker-anf] A picker launch on a device with no resolvable
 * DocumentsUI throws ActivityNotFoundException out of startActivityForResult;
 * [launchPickerSafely] must keep it from crashing the app, log it and tell the
 * user, and leave the normal path untouched.
 */
class SafePickerLaunchTest {

    private val logs = mutableListOf<String>()
    private var notified = 0

    private fun run(launch: () -> Unit): Boolean =
        launchPickerSafely(launch = launch, log = { logs += it }, notify = { notified++ })

    @Test
    fun `a missing picker does not escape, and is logged and shown`() {
        // A launcher that behaves like startActivityForResult on a ROM with no
        // DocumentsUI.
        val launched = run { throw ActivityNotFoundException("No Activity found to handle Intent") }

        assertFalse(launched)
        assertEquals(1, logs.size)
        assertTrue(logs.single(), logs.single().startsWith("picker unavailable:"))
        assertEquals(1, notified)
    }

    @Test
    fun `the normal path launches once and says nothing`() {
        var calls = 0
        val launched = run { calls++ }

        assertTrue(launched)
        assertEquals(1, calls)
        assertTrue(logs.isEmpty())
        assertEquals(0, notified)
    }

    @Test
    fun `other failures still propagate as before`() {
        try {
            run { throw IllegalStateException("launcher not registered") }
            fail("IllegalStateException was swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("launcher not registered", e.message)
        }
        assertTrue(logs.isEmpty())
        assertEquals(0, notified)
    }
}
