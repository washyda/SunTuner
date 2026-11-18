package io.washy.sm8750.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.washy.sm8750.tuner.PlanState
import java.util.Locale

internal enum class Tone { POSITIVE, WARNING, INFO, DANGER, NEUTRAL }

@Composable
internal fun toneColors(tone: Tone): Pair<Color, Color> {
    val ext = TunerThemeTokens.extended
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        Tone.POSITIVE -> ext.positiveContainer to ext.positive
        Tone.WARNING -> ext.warningContainer to ext.warning
        Tone.INFO -> ext.infoContainer to ext.info
        Tone.DANGER -> ext.dangerContainer to ext.danger
        Tone.NEUTRAL -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
}

@Composable
internal fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val (bg, fg) = toneColors(tone)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
internal fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    deltaText: String? = null,
    deltaTone: Tone = Tone.NEUTRAL,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (deltaText != null) {
                    Spacer(Modifier.padding(horizontal = 2.dp))
                    StatusPill(deltaText, deltaTone)
                }
            }
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold).tabular(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun KeyValuePill(label: String, value: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier) {
    val (_, accent) = toneColors(tone)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold).tabular(),
                color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurface else accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun planStateTag(state: PlanState): Pair<String, Tone> = when (state) {
    PlanState.STOCK_UNMODIFIED -> "原厂电压" to Tone.NEUTRAL
    PlanState.CDT_PENDING_REBOOT -> "CDT 已写·待重启" to Tone.WARNING
    PlanState.CDT_ACTIVE -> "CDT 冷启动已生效" to Tone.POSITIVE
    PlanState.RESTORE_PENDING_REBOOT -> "已还原·待重启" to Tone.WARNING
    PlanState.EXTERNAL_RUNTIME -> "外部运行时校准" to Tone.DANGER
    PlanState.CORRUPT_CDT -> "CDT 分区异常" to Tone.DANGER
}

internal fun decimal(value: Double, digits: Int = 1): String = String.format(Locale.ROOT, "%.${digits}f", value)
internal fun signed(value: Double, digits: Int = 1): String = String.format(Locale.ROOT, "%+.${digits}f", value)
