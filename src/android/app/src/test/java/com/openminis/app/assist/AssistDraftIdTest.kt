package com.openminis.app.assist

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-assist-draft-id] One assist gesture must map to exactly one draft chat.
 *
 * The hook re-delivers the same `trigger_id` on every delivery of a gesture
 * (cold onCreate, onNewIntent, Activity relaunch). If any of those minted a
 * fresh `__new__` id the chat route would flip, ChatScreen would remount,
 * SessionActivityTracker would emit setAbsent/setPresent and the FGS would be
 * started and stopped within milliseconds — the crash pinned by
 * [com.openminis.app.service.FgsStopGuardTest]. So the id is a pure function
 * of the gesture, resolved before composition, and a duplicate never mints.
 */
class AssistDraftIdTest {

    private fun resolve(triggerId: Long, carried: String?, duplicate: Boolean = false) =
        resolveAssistDraftSessionId(triggerId, carried, duplicate)

    @Test
    fun sameTriggerIdResolvesToTheSameDraftEveryTime() {
        val first = resolve(triggerId = 987_654L, carried = null)
        val second = resolve(triggerId = 987_654L, carried = null)
        assertEquals(first, second)
        assertEquals("__new__assist-987654", first)
    }

    @Test
    fun differentTriggerIdsResolveToDifferentDrafts() {
        val a = resolve(triggerId = 1_000L, carried = null)
        val b = resolve(triggerId = 1_001L, carried = null)
        assertTrue(a != b)
    }

    @Test
    fun firstDeliveryWithTriggerIdIgnoresACarriedRealSession() {
        assertEquals(
            "__new__assist-42",
            resolve(triggerId = 42L, carried = "sess_abc123", duplicate = false),
        )
    }

    @Test
    fun firstDeliveryWithNoTriggerIdAndNoCarriedIdReturnsNull() {
        assertNull(resolve(triggerId = -1L, carried = null, duplicate = false))
    }

    @Test
    fun duplicateReusesACarriedDraftId() {
        assertEquals(
            "__new__legacy-draft",
            resolve(triggerId = 7L, carried = "__new__legacy-draft", duplicate = true),
        )
    }

    @Test
    fun duplicateReusesACarriedRealSessionId() {
        assertEquals(
            "sess_abc123",
            resolve(triggerId = 7L, carried = "sess_abc123", duplicate = true),
        )
    }

    @Test
    fun duplicateWithoutCarriedIdFallsBackToTheTriggerRoute() {
        assertEquals(
            "__new__assist-7",
            resolve(triggerId = 7L, carried = null, duplicate = true),
        )
    }

    @Test
    fun duplicateWithNeitherCarriedIdNorTriggerIdReturnsNull() {
        assertNull(resolve(triggerId = -1L, carried = null, duplicate = true))
    }

    // --- source guards: no assist path may mint a random draft -------------

    @Test
    fun appNavigationRoutesOpenAssistThroughTheStableRoute() {
        val source = ProductionSources.read("ui/navigation/AppNavigation.kt")
        assertTrue(
            "OpenAssist must still be handled by AppNavigation",
            source.contains("is DeepLinkAction.OpenAssist ->"),
        )
        assertFalse(
            "OpenAssist must not mint a random draft",
            source.contains(
                "is DeepLinkAction.OpenAssist -> " +
                    "Routes.chat(\"__new__\${java.util.UUID.randomUUID()}\")",
            ),
        )
    }

    @Test
    fun mainActivityNeverPairsOpenAssistWithARandomUuid() {
        val source = ProductionSources.read("MainActivity.kt")
        assertTrue(
            "MainActivity lines mentioning OpenAssist must not mint a random UUID",
            source.lineSequence().none { it.contains("OpenAssist") && it.contains("randomUUID") },
        )
    }

    /**
     * A config change re-creates the Activity on the same route. Releasing
     * presence in `onDestroy` there made the FG service stop and start again
     * ~600 ms apart for every font-size/rotation change (measured on dali,
     * 2026-11-15): two stop/start pairs for one font_scale round trip.
     */
    @Test
    fun activityRecreateDoesNotReleasePresence() {
        val source = ProductionSources.read("MainActivity.kt")
        val onDestroy = source.substringAfter("override fun onDestroy() {")
            .substringBefore("super.onDestroy()")
        assertTrue(
            "onDestroy must keep presence across a configuration change",
            onDestroy.contains("if (!isChangingConfigurations)"),
        )
        assertTrue(
            "the release itself must stay inside that guard",
            onDestroy.indexOf("if (!isChangingConfigurations)") <
                onDestroy.indexOf("setAbsent("),
        )
    }
}
