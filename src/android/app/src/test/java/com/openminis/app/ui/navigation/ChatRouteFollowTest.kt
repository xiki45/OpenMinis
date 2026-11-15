package com.openminis.app.ui.navigation

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-chat-route-follows] A warm navigation to another `chat/{id}` must
 * change the pane, not rebuild it around the previous chat.
 *
 * Measured on dali 2026-11-15: with the app already showing session A, ten
 * assist entries in 500 ms produced ten `chat/__new__assist-…` routes, nine
 * ChatScreen remounts of A, nine presence clear/set pairs and four
 * AgentForegroundService create/destroy cycles - the screen never left A.
 *
 * The rule is "act on a route argument that CHANGED", not "act whenever the
 * argument differs from the selection": a configuration change or process
 * restore repeats the same argument and must not pull the user back to it.
 */
class ChatRouteFollowTest {

    @Test
    fun `a changed route argument opens that chat`() {
        assertEquals(
            "__new__assist-900021",
            routeArgumentToOpen("__new__assist-900021", "__new__assist-900020"),
        )
    }

    @Test
    fun `the same argument is a no-op`() {
        assertNull(routeArgumentToOpen("sess-a", "sess-a"))
    }

    @Test
    fun `a null argument is a no-op`() {
        assertNull(routeArgumentToOpen(null, "sess-a"))
        assertNull(routeArgumentToOpen(null, null))
    }

    /**
     * The first argument a scaffold sees is its seeded selection, not a change:
     * a cold start on `chat/{id}` must not navigate a second time.
     */
    @Test
    fun `first argument is recorded, not re-opened`() {
        assertNull(routeArgumentToOpen("sess-a", "sess-a"))
        assertEquals("sess-b", routeArgumentToOpen("sess-b", "sess-a"))
    }

    @Test
    fun `the scaffold follows route changes through the pane navigator`() {
        val source = ProductionSources.read("ui/navigation/ChatSplitScaffold.kt")
        assertTrue(source.contains("LaunchedEffect(initialSessionId)"))
        assertTrue(source.contains("var seenRouteSession by rememberSaveable"))
        assertTrue(
            "the change must go through the pane navigator, not a NavHost push",
            source.contains(
                "navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, target)",
            ),
        )
    }
}
