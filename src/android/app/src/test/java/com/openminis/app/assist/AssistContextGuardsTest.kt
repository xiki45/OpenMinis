package com.openminis.app.assist

import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.deeplink.monotonicNowMs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：assist 通话的两条不变量。
 *
 * 1. [AssistContext.NodeBudget] 必须真的在计数。
 *    原实现把"已遍历节点数"沿 `walk` 的返回值传递，但 `walk` 体内从不自增，
 *    于是 `MAX_NODES = 400` 变成永不成立的条件 —— 一棵几千节点的屏幕树会被
 *    完整遍历，而且发生在 onHandleAssist 的主线程上。这里把"取节点"收敛成
 *    唯一入口，测试锁住上限本身。
 *
 * 2. [DeepLinkCoordinator.pendingAssist] 必须有时效与"不覆盖真实上下文"的语义。
 *    它是进程级单例，写入方失败时不会自动清理；没有 TTL 的话，几分钟前另一块
 *    屏幕的内容会被下一个新会话当成"用户此刻在看什么"自动发给 agent。
 */
class AssistContextGuardsTest {

    @After
    fun tearDown() {
        DeepLinkCoordinator.consumePendingAssist()
    }

    // ---------- 1. 节点预算 ----------

    @Test
    fun `budget allows exactly max nodes then refuses`() {
        val budget = AssistContext.NodeBudget(3)
        assertFalse(budget.exhausted)
        assertTrue(budget.take())
        assertTrue(budget.take())
        assertTrue(budget.take())
        assertEquals(3L, budget.used.toLong())
        assertTrue("上限用尽后必须报告 exhausted", budget.exhausted)
        assertFalse("超过上限后不能再取", budget.take())
        assertEquals("used 不得越界", 3L, budget.used.toLong())
    }

    @Test
    fun `budget of zero takes nothing`() {
        val budget = AssistContext.NodeBudget(0)
        assertTrue(budget.exhausted)
        assertFalse(budget.take())
        assertEquals(0L, budget.used.toLong())
    }

    @Test
    fun `budget used increments monotonically`() {
        val budget = AssistContext.NodeBudget(400)
        repeat(400) { i ->
            assertTrue(budget.take())
            assertEquals((i + 1).toLong(), budget.used.toLong())
        }
        assertFalse(budget.take())
        assertEquals(400L, budget.used.toLong())
    }

    // ---------- 2. 上下文时效 ----------

    @Test
    fun `freshness is inclusive at the ttl boundary`() {
        val ttl = DeepLinkCoordinator.ASSIST_CONTEXT_TTL_MS
        val t0 = 1_000L
        assertTrue(DeepLinkCoordinator.isAssistFresh(t0, t0, ttl))
        assertTrue(DeepLinkCoordinator.isAssistFresh(t0, t0 + ttl, ttl))
        assertFalse("超出一毫秒即过期", DeepLinkCoordinator.isAssistFresh(t0, t0 + ttl + 1, ttl))
        assertFalse("时间倒退（时钟异常）按过期处理", DeepLinkCoordinator.isAssistFresh(t0, t0 - 1, ttl))
    }

    @Test
    fun `latch does not clobber a fresh real context`() {
        DeepLinkCoordinator.setPendingAssist("用户在看的页面文本", screenshotPath = null)
        DeepLinkCoordinator.latchAssistEntry()

        val pending = DeepLinkCoordinator.pendingAssist.value
        assertNotNull("门闩不应清掉刚写入的真实上下文", pending)
        assertEquals("用户在看的页面文本", pending!!.text)
    }

    @Test
    fun `latch replaces an expired context with an empty latch`() {
        // 造一个已过期的上下文：显式传入旧的创建时间戳（模拟"多次前的唤起
        // 因拉起失败而残留"）。
        val staleAt = monotonicNowMs() - DeepLinkCoordinator.ASSIST_CONTEXT_TTL_MS - 5_000L
        DeepLinkCoordinator.setPendingAssist("很久以前的屏幕", null, staleAt)
        assertFalse(DeepLinkCoordinator.isAssistFresh(staleAt))

        DeepLinkCoordinator.latchAssistEntry()

        val latched = DeepLinkCoordinator.pendingAssist.value
        assertNotNull("过期上下文应被门闩替换", latched)
        assertNull("门闩本身不携带文本", latched!!.text)
        assertTrue("替换后的门闩必须是新鲜的", DeepLinkCoordinator.isAssistFresh(latched.createdAtMs))
    }

    @Test
    fun `pending assist carries a creation timestamp`() {
        DeepLinkCoordinator.setPendingAssist("x")
        val pending = DeepLinkCoordinator.pendingAssist.value
        assertNotNull(pending)
        assertTrue(
            "createdAtMs 应该是一个近期的单调时间戳",
            DeepLinkCoordinator.isAssistFresh(pending!!.createdAtMs),
        )
    }
}
