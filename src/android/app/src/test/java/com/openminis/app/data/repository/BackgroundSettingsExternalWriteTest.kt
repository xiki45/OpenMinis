package com.openminis.app.data.repository

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `minis-config set background.*` writes the prefs file directly, not through
 * the repository's setters. The repository's flows - what the foreground
 * service reads - must follow such a write without an app restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundSettingsExternalWriteTest {

    @Test
    fun `a direct prefs write reaches the flows`() {
        val ctx = RuntimeEnvironment.getApplication()
        val repo = BackgroundSettingsRepository(ctx)
        repo.setDynamicIslandEnabled(true)
        repo.setBackgroundOverlayEnabled(true)
        assertTrue(repo.taskNotificationsEnabled.value)

        // What ConfigBuiltins' PrefsBoolField does.
        ctx.getSharedPreferences("background_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("dynamicIslandEnabled", false)
            .putBoolean("backgroundOverlayEnabled", false)
            .putBoolean("taskNotificationsEnabled", false)
            .apply()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertFalse(repo.dynamicIslandEnabled.value)
        assertFalse(repo.backgroundOverlayEnabled.value)
        assertFalse(repo.taskNotificationsEnabled.value)
    }
}
