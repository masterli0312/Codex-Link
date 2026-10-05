package com.codex.quota

import com.codex.quota.data.cloud.*
import org.junit.Assert.*
import org.junit.Test

class CloudHistoryTest {
    private fun message(id: String) = CloudMessage("assistant", "reply $id", "$id:a")
    private fun details(vararg ids: String, cursor: String = "older") = CloudDetails(CloudTask("thread", "Title"), ids.map(::message), historyCursor = cursor)
    @Test fun opaquePagesArePrependedInOrderAndOverlapIsNotDuplicated() {
        val current = details("t3", "t4")
        val merged = CloudHistory.prepend(current, details("t1", "t2", "t3", cursor = "oldest"), "older")
        assertEquals(listOf("t1:a", "t2:a", "t3:a", "t4:a"), CloudHistory.messages(merged).map { it.id })
        assertEquals("oldest", CloudHistory.cursor(merged))
        assertEquals(current.task, merged.task)
        val refreshed = CloudHistory.refreshed(merged, details("t4", "t5", cursor = "new-tail-cursor"))
        assertEquals(listOf("t1:a", "t2:a", "t3:a", "t4:a", "t5:a"), CloudHistory.messages(refreshed).map { it.id })
        assertEquals("oldest", CloudHistory.cursor(refreshed))
    }
    @Test fun exhaustedPagesRemainExhaustedAfterPolling() {
        val loaded = CloudHistory.prepend(details("new"), details("old", cursor = ""), "older")
        assertEquals("", CloudHistory.cursor(CloudHistory.refreshed(loaded, details("new", "next"))))
    }
    @Test fun anotherThreadOrRepeatedCursorCannotEnterCurrentHistory() {
        val current = details("new")
        assertTrue(runCatching { CloudHistory.prepend(current, details("old"), "older") }.isFailure)
        assertTrue(runCatching { CloudHistory.prepend(current, details("old", cursor = "").copy(task = CloudTask("other", "Other")), "older") }.isFailure)
        val other = details("next").copy(task = CloudTask("other", "Other"))
        assertEquals(other, CloudHistory.refreshed(current, other))
    }
    @Test fun largeHistoryIsBoundedAndClearlyMarked() {
        val current = details("new")
        val older = current.copy(messages = (1..400).map { message("old-$it") }, historyCursor = "even-older")
        val loaded = CloudHistory.prepend(current, older, "older")
        assertTrue(loaded.olderMessages.size <= 300); assertTrue(loaded.historyLimited)
        assertEquals("", CloudHistory.cursor(loaded)); assertEquals("new:a", CloudHistory.messages(loaded).last().id)
    }
}
