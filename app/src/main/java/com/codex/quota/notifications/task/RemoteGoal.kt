package com.codex.quota.notifications.task

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class RemoteGoal(val thread_id: String, val objective: String, val status: String, val tokens_used: Long,
    val time_used_seconds: Long, val created_at: Long, val updated_at: Long, val token_budget: Long? = null)

object RemoteGoalRules {
    val actions = setOf("goal_read", "goal_start", "goal_pause", "goal_resume", "goal_clear")
    val rootActions = setOf("goal_start", "goal_resume")
    fun validObjective(value: String) = value.isNotBlank() && value.codePointCount(0, value.length) <= 4000 && value.toByteArray().size <= 16384
    fun valid(goal: RemoteGoal, thread: String) = goal.thread_id == thread && validObjective(goal.objective) &&
        goal.status in setOf("active", "paused", "blocked", "usageLimited", "budgetLimited", "complete") &&
        listOf(goal.tokens_used, goal.time_used_seconds, goal.created_at, goal.updated_at).all { it >= 0 } &&
        (goal.token_budget == null || goal.token_budget > 0)
    fun command(ref: RemoteThreadRef, conversation: String, action: String, goal: RemoteGoal?, rootId: String = "",
        objective: String = "", budget: Long? = null): RemoteCommand {
        require(action in actions && RemoteProtocol.validRef(ref, conversation))
        require(goal == null || valid(goal, ref.thread_id))
        require(action != "goal_start" || goal == null && validObjective(objective) && (budget == null || budget in 1..2_000_000_000))
        require(action != "goal_resume" || goal?.status == "paused")
        require(action !in setOf("goal_pause", "goal_clear") || goal != null)
        return RemoteCommand(UUID.randomUUID().toString(), action, ref.host_id, ref.thread_id, conversation,
            ref.baseline_turn, System.currentTimeMillis(), target_id = rootId, read_thread_id = if (action == "goal_read") ref.thread_id else "",
            objective = if (action == "goal_start") objective else "", token_budget = budget, expected_goal_updated_at = goal?.updated_at ?: 0, expected_goal_created_at = goal?.created_at ?: 0, expected_goal_hash = goal?.let { TaskInbox.hash(it.objective) }.orEmpty())
    }
    fun accepts(command: RemoteCommand, previous: RemoteEvent?, event: RemoteEvent): Boolean = command.action in actions &&
        event.request_id == command.id && event.host_id == command.host_id && event.thread_id == command.thread_id &&
        event.conversation_id == command.conversation_id && (event.seq > (previous?.seq ?: 0) ||
            previous?.attachment_pending == true && !event.attachment_pending && event.seq == previous.seq && event.id == previous.id &&
                event.at == previous.at && event.status == previous.status)
}
