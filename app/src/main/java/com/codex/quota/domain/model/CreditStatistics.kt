package com.codex.quota.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class CreditObservation(val id: Long, val accountId: String, val observedAtEpochMs: Long, val balance: Double, val change: Double?)
data class CreditHour(val startEpochMs: Long, val endEpochMs: Long, val decrease: Double, val increase: Double, val changes: Int, val observations: Int)
data class CreditStatisticsSummary(val hourly: List<CreditHour>, val decrease24h: Double, val increase24h: Double, val changes24h: Int)

/** Group balance changes by detection time, never infer billing time or interpolate unsampled hours. */
object CreditStatistics {
    fun calculate(entries: List<CreditObservation>, accountId: String, now: Long, zone: ZoneId): CreditStatisticsSummary {
        val end = Instant.ofEpochMilli(now)
        val valid = entries.filter { it.accountId == accountId && it.observedAtEpochMs in 1..now &&
            it.balance.isFinite() && it.balance >= 0 && (it.change == null || it.change.isFinite()) }.distinctBy { it.id }
        fun total(rows: List<CreditObservation>, decrease: Boolean): Double = rows.fold(BigDecimal.ZERO) { value, row ->
            val change = row.change ?: 0.0
            value + BigDecimal.valueOf(if (decrease) (-change).coerceAtLeast(0.0) else change.coerceAtLeast(0.0))
        }.toDouble()
        val groups = valid.groupBy { Instant.ofEpochMilli(it.observedAtEpochMs).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli() }
        val hour = end.atZone(zone).truncatedTo(ChronoUnit.HOURS)
        val hourly = (0L..23L).map { offset ->
            val start = hour.minusHours(offset).toInstant().toEpochMilli()
            val rows = groups[start].orEmpty()
            CreditHour(start, start + 3_600_000, total(rows, true), total(rows, false), rows.count { it.change != null }, rows.size)
        }
        val recent = valid.filter { it.observedAtEpochMs > now - 86_400_000 }
        return CreditStatisticsSummary(hourly, total(recent, true), total(recent, false), recent.count { it.change != null })
    }
}
