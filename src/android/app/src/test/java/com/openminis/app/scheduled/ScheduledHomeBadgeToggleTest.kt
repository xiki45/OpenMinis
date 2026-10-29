package com.openminis.app.scheduled

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [T-android-sched-badge-toggle] The home clock icon's count badge can be
 * turned off from the Scheduled Tasks screen. Default on; the setting
 * persists; the gate hides the badge when off or when nothing is enabled.
 */
@RunWith(RobolectricTestRunner::class)
class ScheduledHomeBadgeToggleTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ctx.getSharedPreferences("minis_scheduled_tasks_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `badge is shown by default`() {
        assertTrue(ScheduledTaskStore(ctx).showHomeBadge())
        assertTrue(runBlocking { ScheduledTaskStore(ctx).observeShowHomeBadge().first() })
    }

    @Test
    fun `turning it off persists across store instances`() {
        ScheduledTaskStore(ctx).setShowHomeBadge(false)
        assertFalse(ScheduledTaskStore(ctx).showHomeBadge())
        assertFalse(runBlocking { ScheduledTaskStore(ctx).observeShowHomeBadge().first() })
        ScheduledTaskStore(ctx).setShowHomeBadge(true)
        assertTrue(ScheduledTaskStore(ctx).showHomeBadge())
    }

    @Test
    fun `clearing tasks does not reset the badge setting`() {
        val store = ScheduledTaskStore(ctx)
        store.setShowHomeBadge(false)
        store.clear()
        assertFalse(store.showHomeBadge())
    }

    @Test
    fun `badge gate`() {
        assertEquals(2, ScheduledTaskStore.homeBadgeCount(2, showBadge = true))
        assertNull("off hides a non-zero count", ScheduledTaskStore.homeBadgeCount(2, showBadge = false))
        assertNull("no enabled task, no badge", ScheduledTaskStore.homeBadgeCount(0, showBadge = true))
        assertNull(ScheduledTaskStore.homeBadgeCount(0, showBadge = false))
    }
}
