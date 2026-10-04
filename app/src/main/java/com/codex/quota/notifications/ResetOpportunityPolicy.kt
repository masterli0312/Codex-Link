package com.codex.quota.notifications

data class ResetOpportunityObservation(val accountId: String, val count: Int?, val fetchedAtEpochMs: Long)

/** The first reading is a baseline. A decrease (including spending a Reset) never produces an alert. */
internal fun isResetOpportunityIncrease(previous: ResetOpportunityObservation?, current: ResetOpportunityObservation, enabled: Boolean): Boolean {
    if (!enabled || previous == null || previous.accountId != current.accountId ||
        current.fetchedAtEpochMs <= previous.fetchedAtEpochMs || previous.fetchedAtEpochMs <= 0) return false
    val oldCount = previous.count?.takeIf { it >= 0 } ?: return false
    val newCount = current.count?.takeIf { it >= 0 } ?: return false
    return newCount > oldCount
}
