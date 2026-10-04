package com.codex.quota

import com.codex.quota.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CreditStatisticsTest {
    private val now = Instant.parse("2026-10-02T06:30:00Z").toEpochMilli()
    private fun sample(id: Long, at: String, change: Double?, account: String = "li") =
        CreditObservation(id, account, Instant.parse(at).toEpochMilli(), 100.0, change)

    @Test fun hourlyDeductionsAndAdditionsAreSeparateAndUnknownHoursRemainUnknown() {
        val entries = listOf(sample(1, "2026-10-02T05:20:00Z", null), sample(2, "2026-10-02T06:10:00Z", -2.5),
            sample(3, "2026-10-02T06:20:00Z", 10.0), sample(4, "2026-10-02T06:25:00Z", -1.2))
        val stats = CreditStatistics.calculate(entries, "li", now, ZoneId.of("Asia/Shanghai"))
        assertEquals(3.7, stats.hourly.first().decrease, 0.000001)
        assertEquals(10.0, stats.hourly.first().increase, 0.000001)
        assertEquals(3, stats.hourly.first().changes)
        assertEquals(0, stats.hourly[2].observations)
        assertEquals(3.7, stats.decrease24h, 0.000001)
    }

    @Test fun anotherAccountFutureInvalidAndDuplicateRecordsDoNotAffectTotals() {
        val record = sample(1, "2026-10-02T06:10:00Z", -2.5)
        val entries = listOf(record, record, sample(2, "2026-10-02T06:15:00Z", -20.0, "masterli"),
            sample(3, "2026-10-02T07:00:00Z", -10.0), sample(4, "2026-10-02T06:12:00Z", Double.NaN),
            sample(5, "2026-10-02T06:13:00Z", -1.0).copy(balance = -1.0))
        val stats = CreditStatistics.calculate(entries, "li", now, ZoneId.of("Asia/Shanghai"))
        assertEquals(2.5, stats.decrease24h, 0.000001)
        assertEquals(1, stats.hourly.first().observations)
    }

    @Test fun hourBoundariesUseDeviceTimezoneIncludingFractionalOffsetsAndDst() {
        val fractional = CreditStatistics.calculate(emptyList(), "li", now, ZoneId.of("Asia/Kathmandu"))
        assertEquals(Instant.parse("2026-10-02T06:15:00Z").toEpochMilli(), fractional.hourly.first().startEpochMs)
        val dstNow = Instant.parse("2026-11-01T07:10:00Z").toEpochMilli()
        val dst = CreditStatistics.calculate(emptyList(), "li", dstNow, ZoneId.of("America/New_York"))
        assertEquals(24, dst.hourly.map { it.startEpochMs }.distinct().size)
        assertTrue(dst.hourly.zipWithNext().all { (a, b) -> a.startEpochMs - b.startEpochMs == 3_600_000L })
    }
}
