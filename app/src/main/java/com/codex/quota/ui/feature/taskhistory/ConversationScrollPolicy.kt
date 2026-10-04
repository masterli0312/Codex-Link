package com.codex.quota.ui.feature.taskhistory

/** Follow measured growth without resetting the list's anchor for every text delta. */
internal object ConversationScrollPolicy {
    sealed interface Step {
        data object None : Step
        data class Reveal(val index: Int) : Step
        data class Move(val pixels: Int) : Step
    }

    fun nearEnd(total: Int, lastIndex: Int, lastBottom: Int, viewportEnd: Int, tolerance: Int): Boolean =
        total > 0 && lastIndex == total - 1 && lastBottom <= viewportEnd + tolerance

    fun follow(expected: Int, measured: Int, lastIndex: Int, lastBottom: Int, viewportEnd: Int,
        enabled: Boolean, interacting: Boolean): Step = when {
        !enabled || interacting || expected <= 0 || measured != expected || lastIndex < 0 -> Step.None
        lastIndex < expected - 1 -> Step.Reveal(expected - 1)
        lastBottom > viewportEnd -> Step.Move(lastBottom - viewportEnd)
        else -> Step.None
    }
}
