package com.openminis.app.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import com.openminis.app.logging.AppLogger

/**
 * 把 [AssistStructure]（当前屏幕视图树）和 [AssistContent]（当前浏览的内容）
 * 展平为一段 agent 可读的结构化文本。
 *
 * 这段文本最终作为一条"assist 用户消息"注入 minis 的 chat 会话，让 agent
 * 知道用户此刻正在看什么 / 用什么应用，从而给出情境化的响应。
 *
 * 展平策略（与 a11y 读屏互补，避免无限递归）：
 *  - 只收集叶子节点附近有意义的 [text] / [contentDescription]
 *  - 用 [depth] 控制缩进，超过 [MAX_DEPTH] 不再下钻
 *  - 全量节点会爆炸，这里做去重 + 长度截断
 */
object AssistContext {

    private const val TAG = "AssistContext"
    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 400
    private const val MAX_LEN = 4000

    /**
     * @return 拼接好的上下文文本；为空表示没有可用内容。
     */
    fun flatten(structure: AssistStructure?, content: AssistContent?): String {
        val sb = StringBuilder()

        // ---- 1. 当前浏览的网页 / 应用内容 ----
        if (content != null) {
            try {
                content.webUri?.let { uri ->
                    sb.appendLine("● 当前网页: $uri")
                }
                // NOTE: AssistContent has no getTitle() in any API level
                // (verified against AOSP api 35/36) — the page title, if any,
                // rides the extras below (e.g. Intent.EXTRA_TITLE) instead.
                val extras = content.extras
                if (extras != null) {
                    // AssistContent 可能带网页正文文本（由浏览器填充）
                    val text = extras.getString("android.intent.extra.TEXT")
                    if (!text.isNullOrBlank()) {
                        sb.appendLine("● 页面正文: ${text.take(600)}")
                    }
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "flatten content failed: ${t.message}")
            }
        }

        // ---- 2. 当前屏幕的视图树 ----
        if (structure != null) {
            val nodes = StringBuilder()
            val budget = NodeBudget(MAX_NODES)
            try {
                structure.getWindowNodeCount().let { winCount ->
                    for (w in 0 until winCount) {
                        if (budget.exhausted) break
                        val node = structure.getWindowNodeAt(w).rootViewNode
                        walk(node, 0, nodes, budget)
                    }
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "flatten structure failed: ${t.message}")
            }
            AppLogger.info(TAG, "structure flattened: ${budget.used}/$MAX_NODES nodes, ${nodes.length} chars")

            val treeText = nodes.toString().trim()
            if (treeText.isNotEmpty()) {
                sb.appendLine("● 当前屏幕视图:")
                sb.appendLine(treeText.take(MAX_LEN))
            }
        }

        return sb.toString().trim()
    }

    /**
     * 节点预算。
     *
     * 原实现靠 `walk` 的返回值链式传计数，但函数体里从来没自增过 —— 于是
     * `MAX_NODES` 变成永不成立的条件，整棵树会被完整遍历（长列表/网页可达
     * 数千节点，且发生在 `onHandleAssist` 的主线程上）。把"取一个节点"收敛到
     * 单一入口后，计数不再可能被漏掉：`walk` 必须先成功 [take]，才能继续。
     */
    internal class NodeBudget(private val max: Int) {
        var used = 0
            private set

        val exhausted: Boolean get() = used >= max

        fun take(): Boolean {
            if (used >= max) return false
            used++
            return true
        }
    }

    /** 深度优先遍历 ViewNode，消耗 [budget]。 */
    private fun walk(
        node: AssistStructure.ViewNode,
        depth: Int,
        out: StringBuilder,
        budget: NodeBudget,
    ) {
        if (depth > MAX_DEPTH) return
        if (!budget.take()) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        // 收集"有实质内容"的节点，避免刷屏无意义容器
        if ((!text.isNullOrBlank()) || (!desc.isNullOrBlank())) {
            val label = when {
                !text.isNullOrBlank() && !desc.isNullOrBlank() -> "$text ($desc)"
                !text.isNullOrBlank() -> text
                else -> desc!!
            }
            if (label.isNotBlank()) {
                out.append("  ".repeat(depth.coerceAtMost(8)))
                    .append(label)
                    .append('\n')
            }
        }

        val childCount = node.childCount.coerceAtMost(64) // 防恶意超大树
        for (i in 0 until childCount) {
            if (budget.exhausted) break
            val child = try { node.getChildAt(i) } catch (t: Throwable) { null } ?: continue
            walk(child, depth + 1, out, budget)
        }
    }
}
