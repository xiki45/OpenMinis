package com.openminis.app.ui.components

import android.app.Application
import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * [T-android-picker-anf] The closest this gets to a ROM without DocumentsUI
 * without a device: a real ComponentActivity, a really registered
 * OpenMultipleDocuments launcher (the chat "Add File" contract), and
 * Robolectric's checkActivities(true), which makes startActivity throw
 * ActivityNotFoundException when no activity resolves the Intent - and in
 * Robolectric nothing resolves ACTION_OPEN_DOCUMENT.
 *
 * Gap from a real device: the Toast text itself is not checked (no app
 * resources in this test setup); the Intent goes through Robolectric's shadow
 * instrumentation, not the framework's ActivityTaskManager, and the "no
 * activity" state is simulated rather than a ROM's real package set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SafePickerLaunchRobolectricTest {

    private fun activity(): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(activity.application).checkActivities(true)
        return activity
    }

    @Test
    fun `without the helper an unresolvable picker throws out of launch`() {
        val activity = activity()
        val launcher = activity.activityResultRegistry.register("bare", ActivityResultContracts.OpenMultipleDocuments()) {}
        try {
            launcher.launch(arrayOf("*/*"))
            fail("expected ActivityNotFoundException - the simulation does not reproduce the crash")
        } catch (_: ActivityNotFoundException) {
        }
    }

    @Test
    fun `with the helper the same launch is caught, logged and reported`() {
        val activity = activity()
        var results = 0
        val launcher = activity.activityResultRegistry.register("safe", ActivityResultContracts.OpenMultipleDocuments()) {
            results++
        }
        val logs = mutableListOf<String>()
        var notified = 0

        // safeLaunchPicker = launchPickerSafely + AppLogger + a Toast of a
        // string resource; this test setup has no app resources, so the
        // decision part is driven with the real launcher and the UI part is
        // recorded instead.
        val launched = launchPickerSafely(
            launch = { launcher.launch(arrayOf("*/*")) },
            log = { logs += it },
            notify = { notified++ },
        )

        assertFalse(launched)
        assertEquals(1, logs.size)
        assertTrue(logs.single(), logs.single().startsWith("picker unavailable:"))
        assertEquals(1, notified)
        assertEquals(0, results)
    }

    @Test
    fun `when the picker resolves it is launched and no toast is shown`() {
        val activity = activity()
        shadowOf(activity.application).checkActivities(false)
        val launcher = activity.activityResultRegistry.register("ok", ActivityResultContracts.OpenMultipleDocuments()) {}

        assertTrue(launcher.safeLaunchPicker(arrayOf("*/*"), activity, "Test"))
        assertEquals("android.intent.action.OPEN_DOCUMENT", shadowOf(activity).nextStartedActivityForResult.intent.action)
        assertNull(ShadowToast.getLatestToast())
    }
}
