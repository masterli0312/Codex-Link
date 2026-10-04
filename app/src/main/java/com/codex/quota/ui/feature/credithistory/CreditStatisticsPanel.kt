package com.codex.quota.ui.feature.credithistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codex.quota.R
import com.codex.quota.domain.model.CreditStatisticsSummary
import com.codex.quota.ui.components.SectionTitle
import java.text.NumberFormat

@Composable
internal fun CreditStatisticsPanel(summary: CreditStatisticsSummary, compact: Boolean = false) {
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 6 } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.credit_observed_24h), Icons.Outlined.BarChart)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 260.dp || LocalConfiguration.current.fontScale > 1.3f
            val decrease = if (summary.changes24h > 0) numbers.format(summary.decrease24h) else stringResource(R.string.value_unavailable)
            val increase = if (summary.changes24h > 0) numbers.format(summary.increase24h) else stringResource(R.string.value_unavailable)
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CreditMetric(stringResource(R.string.credit_observed_decrease), decrease, Modifier.fillMaxWidth(), true)
                CreditMetric(stringResource(R.string.credit_observed_increase), increase, Modifier.fillMaxWidth(), false)
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CreditMetric(stringResource(R.string.credit_observed_decrease), decrease, Modifier.weight(1f), true)
                CreditMetric(stringResource(R.string.credit_observed_increase), increase, Modifier.weight(1f), false)
            }
        }
        Text(stringResource(if (compact) R.string.credit_detail_sampling_note else R.string.credit_hourly_explanation),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CreditMetric(label: String, value: String, modifier: Modifier, emphasized: Boolean) {
    Surface(modifier, shape = RoundedCornerShape(16.dp),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}
