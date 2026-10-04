package com.codex.quota.notifications.task

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/** Compact display only; the stored provider counts remain exact. */
object RemoteStatusFormat {
    fun compactCount(count: Long, locale: Locale, unit: String, belowUnit: String): String {
        val divisor = if (locale.language == "zh") 10_000 else 1_000
        if (count in 1 until divisor.toLong()) return belowUnit
        return NumberFormat.getIntegerInstance(locale).format((count.coerceAtLeast(0).toDouble() / divisor).roundToLong()) + unit
    }
}
