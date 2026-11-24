package io.washy.sm8750.tuner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.washy.sm8750.tuner.PlanState
import io.washy.sm8750.tuner.R
import java.util.Locale

internal enum class Tone { POSITIVE, WARNING, INFO, DANGER, NEUTRAL }

@Composable
internal fun ScreenHeading(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
        actions()
    }
}

internal fun listSegmentShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) 24.dp else 9.dp
    val bottom = if (index == count - 1) 24.dp else 9.dp
    return RoundedCornerShape(top, top, bottom, bottom)
}

internal enum class TunerSymbol { REFRESH, SHIELD, MORE, DOWN, NEXT, PLAY, DOWNLOAD, ALERT, CHIP, DISPLAY }

@Composable
internal fun TonalAction(
    symbol: TunerSymbol,
    label: String,
    enabled: Boolean,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        if (loading) {
            ExpressiveLoadingIndicator(Modifier.size(24.dp))
        } else {
            SymbolIcon(symbol)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LoadingIndicator(modifier = modifier, color = color)
}

@Composable
internal fun SymbolIcon(symbol: TunerSymbol, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    val refresh = if (symbol == TunerSymbol.REFRESH) painterResource(R.drawable.ic_refresh) else null
    Canvas(modifier.size(22.dp)) {
        fun point(x: Float, y: Float) = Offset(x * size.width, y * size.height)
        val width = 2.dp.toPx()
        val stroke = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(points: List<Offset>, closed: Boolean = false, filled: Boolean = false) {
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                if (closed) close()
            }
            if (filled) drawPath(path, color) else drawPath(path, color, style = stroke)
        }
        when (symbol) {
            TunerSymbol.REFRESH -> refresh?.apply { draw(size, colorFilter = ColorFilter.tint(color)) }
            TunerSymbol.SHIELD -> {
                line(listOf(point(.50f, .09f), point(.85f, .24f), point(.80f, .63f), point(.70f, .78f),
                    point(.50f, .91f), point(.30f, .78f), point(.20f, .63f), point(.15f, .24f)), closed = true)
                line(listOf(point(.35f, .50f), point(.46f, .61f), point(.67f, .39f)))
            }
            TunerSymbol.MORE -> listOf(.23f, .50f, .77f).forEach { drawCircle(color, width * .68f, point(.5f, it)) }
            TunerSymbol.DOWN -> line(listOf(point(.28f, .4f), point(.50f, .63f), point(.72f, .4f)))
            TunerSymbol.NEXT -> line(listOf(point(.38f, .28f), point(.62f, .50f), point(.38f, .72f)))
            TunerSymbol.PLAY -> line(listOf(point(.29f, .17f), point(.83f, .50f), point(.29f, .83f)), closed = true, filled = true)
            TunerSymbol.DOWNLOAD -> {
                line(listOf(point(.5f, .13f), point(.5f, .64f)))
                line(listOf(point(.30f, .44f), point(.50f, .65f), point(.70f, .44f)))
                line(listOf(point(.16f, .65f), point(.16f, .85f), point(.84f, .85f), point(.84f, .65f)))
            }
            TunerSymbol.ALERT -> {
                line(listOf(point(.50f, .12f), point(.92f, .85f), point(.08f, .85f)), closed = true)
                line(listOf(point(.50f, .42f), point(.50f, .59f)))
                drawCircle(color, width * .55f, point(.5f, .72f))
            }
            TunerSymbol.CHIP -> {
                drawRect(color, point(.24f, .24f), Size(size.width * .52f, size.height * .52f), style = stroke)
                for (p in listOf(.35f, .50f, .65f)) {
                    line(listOf(point(p, .09f), point(p, .23f)))
                    line(listOf(point(p, .77f), point(p, .91f)))
                    line(listOf(point(.09f, p), point(.23f, p)))
                    line(listOf(point(.77f, p), point(.91f, p)))
                }
            }
            TunerSymbol.DISPLAY -> {
                drawRect(color, point(.12f, .20f), Size(size.width * .76f, size.height * .52f), style = stroke)
                line(listOf(point(.5f, .72f), point(.5f, .87f)))
                line(listOf(point(.29f, .87f), point(.71f, .87f)))
            }
        }
    }
}

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
    PlanState.STOCK_UNMODIFIED -> "原厂 CDT" to Tone.NEUTRAL
    PlanState.CDT_WRITTEN -> "CDT 已写入" to Tone.POSITIVE
    PlanState.CDT_RECOVERY_REQUIRED -> "CDT 与备份不一致" to Tone.DANGER
    PlanState.EXTERNAL_RUNTIME -> "外部运行时校准" to Tone.DANGER
    PlanState.CORRUPT_CDT -> "CDT 分区异常" to Tone.DANGER
}

internal fun decimal(value: Double, digits: Int = 1): String = String.format(Locale.ROOT, "%.${digits}f", value)
internal fun signed(value: Double, digits: Int = 1): String = String.format(Locale.ROOT, "%+.${digits}f", value)
