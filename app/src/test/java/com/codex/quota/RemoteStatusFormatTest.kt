package com.codex.quota

import com.codex.quota.notifications.task.RemoteStatusFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteStatusFormatTest {
    @Test fun chineseContextUsesTenThousandsWithoutChangingUnderlyingCounts() {
        assertEquals("4万", RemoteStatusFormat.compactCount(44_000, Locale.SIMPLIFIED_CHINESE, "万", "不足1万"))
        assertEquals("26万", RemoteStatusFormat.compactCount(260_000, Locale.SIMPLIFIED_CHINESE, "万", "不足1万"))
        assertEquals("26万", RemoteStatusFormat.compactCount(256_000, Locale.SIMPLIFIED_CHINESE, "万", "不足1万"))
        assertEquals("不足1万", RemoteStatusFormat.compactCount(9_999, Locale.SIMPLIFIED_CHINESE, "万", "不足1万"))
        assertEquals("0万", RemoteStatusFormat.compactCount(0, Locale.SIMPLIFIED_CHINESE, "万", "不足1万"))
    }
    @Test fun englishContextUsesThousands() {
        assertEquals("44k", RemoteStatusFormat.compactCount(44_000, Locale.US, "k", "<1k"))
        assertEquals("<1k", RemoteStatusFormat.compactCount(900, Locale.US, "k", "<1k"))
    }
}
