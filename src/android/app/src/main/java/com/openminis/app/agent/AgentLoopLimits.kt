package com.openminis.app.agent

/**
 * The agent loop's hard tool-round ceiling, shared by the main chat
 * (ChatViewModel.MAX_AGENT_TURNS) and sub-agent helpers / scheduled jobs
 * (HelperRunner.MAX_TURNS), so the two cannot drift apart.
 *
 * Mirrors iOS AIChatViewModel.maxAgentTurns (and helperMaxTurns, defined as
 * it). Raised 200 -> 500 on all three platforms (FEAT-261021-01); it is a
 * backstop behind ToolLoopDetector, not the normal way a turn ends.
 */
object AgentLoopLimits {
    const val MAX_AGENT_TURNS = 500
}
