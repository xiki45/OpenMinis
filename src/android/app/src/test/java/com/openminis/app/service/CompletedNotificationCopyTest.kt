package com.openminis.app.service

import com.openminis.app.ProductionSources
import com.openminis.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-port-sched-completed-copy] After a repeating task's last run the ongoing
 * notification read "任务已完成 / 0 个会话 | 用时 0:01" (Pixel 4a, round 5):
 * nothing is active, present or scheduled any more, so the count is 0. With
 * no session to count, the completed line is the duration alone.
 */
class CompletedNotificationCopyTest {

    @Test
    fun `no session count when nothing is left to count`() {
        assertEquals(R.string.bg_service_notification_text_completed_no_count, completedTextResFor(0))
        assertEquals(R.string.bg_service_notification_text_completed, completedTextResFor(1))
        assertEquals(R.string.bg_service_notification_text_completed, completedTextResFor(3))
    }

    @Test
    fun `the service picks the line by count and passes the right arguments`() {
        val svc = ProductionSources.read("service/AgentForegroundService.kt")
        val branch = svc.substringAfter("AgentPhase.COMPLETED -> if (sessionCount > 0) {").substringBefore("\n            }\n")
        assertTrue(branch.contains("getString(completedTextResFor(sessionCount), sessionLabel, timeString)"))
        assertTrue(branch.contains("getString(completedTextResFor(sessionCount), timeString)"))
    }

    @Test
    fun `every locale with the completed line has the count-free one, same wording`() {
        val res = File(ProductionSources.mainRoot()!!, "../../../../res")
        val withLine = res.listFiles()!!.filter { d ->
            File(d, "strings.xml").takeIf { it.isFile }?.readText()?.contains("name=\"bg_service_notification_text_completed\"") == true
        }
        assertEquals(23, withLine.size)
        for (d in withLine) {
            val full = Regex("name=\"bg_service_notification_text_completed\">([^<]*)<").find(File(d, "strings.xml").readText())!!.groupValues[1]
            val noCount = Regex("name=\"bg_service_notification_text_completed_no_count\">([^<]*)<")
                .find(File(d, "strings_bg_completed.xml").readText())!!.groupValues[1]
            assertEquals(d.name, full.removePrefix("%1\$s | ").replace("%2\$s", "%1\$s"), noCount)
            assertTrue(d.name, !noCount.contains("%2\$s"))
        }
    }
}
