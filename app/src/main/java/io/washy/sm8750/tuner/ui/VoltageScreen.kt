package io.washy.sm8750.tuner.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.washy.sm8750.tuner.BaselineSource
import io.washy.sm8750.tuner.DevicePlan
import io.washy.sm8750.tuner.Level
import io.washy.sm8750.tuner.Operation
import io.washy.sm8750.tuner.PlanState
import io.washy.sm8750.tuner.R
import io.washy.sm8750.tuner.ScreenState

@Composable
internal fun VoltageScreen(
    state: ScreenState,
    onRefresh: () -> Unit,
    onCheckCompatibility: () -> Unit,
    onBackupCdt: () -> Unit,
    onEnableCdt: () -> Unit,
    onRestoreCdt: () -> Unit,
    onRestoreCdtFromFile: () -> Unit,
    onRebootNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = state.plan
    val currentLevels = device?.current.orEmpty()
    val showPower = currentLevels.firstOrNull()?.let { it.coefficient == null } == true
    val partition = state.cdtPartition
    val systemFrequencies = if (partition?.standalone == true && device == null) {
        state.benchmarkDevice?.frequencies.orEmpty()
    } else emptyMap()
    val cdtDisk = partition?.disk ?: device?.cdtDisk
    val legacyCdt = cdtDisk == "legacy_v6"
    val cdtState = when {
        state.cdtUncertain -> null
        partition != null -> partition.state
        else -> device?.state
    }
    var confirmEnable by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }

    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("开启降压") },
            text = { Text("将先备份原厂分区，再写入 CDT 降压标志并校验。重启后可检查电压计划是否变化。") },
            confirmButton = { Button(onClick = { confirmEnable = false; onEnableCdt() }) { Text("写入 CDT") } },
            dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("取消") } },
        )
    }

    if (confirmRestore) {
        val recoveryRequired = cdtState == PlanState.CDT_RECOVERY_REQUIRED
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("还原 CDT 分区") },
            text = {
                Column {
                    Text(
                        when {
                            legacyCdt -> "当前 CDT 由旧版写入。请选择本机原厂备份还原，重启后再开启降压。"
                            recoveryRequired -> "当前分区与原厂备份不一致。将写回原厂备份并校验整个分区。"
                            else -> "可使用自动备份，或选择此前从本机导出的原厂镜像。所选镜像通过校验后会直接写入。"
                        }
                    )
                    TextButton(onClick = { confirmRestore = false; onRestoreCdtFromFile() }) { Text("从镜像文件还原") }
                }
            },
            confirmButton = { Button(onClick = { confirmRestore = false; onRestoreCdt() }) { Text("还原分区") } },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } },
        )
    }

    val busy = state.operation != null
    val (status, tone) = when {
        cdtState == null -> "CDT 状态待确认" to Tone.WARNING
        legacyCdt -> "旧版 CDT · 需还原" to Tone.WARNING
        device?.sm8850 == true && cdtState == PlanState.CDT_WRITTEN -> sm8850CdtState(device)
        partition?.standalone == true && cdtState == PlanState.CDT_WRITTEN -> {
            if (partition.pendingReboot) "CDT 已写 · 待重启" to Tone.WARNING
            else "CDT 已写 · 效果未确认" to Tone.INFO
        }
        else -> planStateTag(cdtState)
    }
    val comparisonBaseline = device?.takeIf {
        !showPower && it.baselineSource == BaselineSource.PERSISTED &&
            (it.state == PlanState.CDT_WRITTEN || it.state == PlanState.EXTERNAL_RUNTIME)
    }?.baseline
    val tables = remember(currentLevels, comparisonBaseline) {
        val stockByDomain = comparisonBaseline.orEmpty().groupBy(Level::domain)
        currentLevels.groupBy(Level::domain).toSortedMap().map { (domain, levels) ->
            val baseline = stockByDomain[domain].orEmpty().filter { it.millivolts != null }.associateBy(Level::khz)
            VoltageTable(domain, levels.sortedByDescending(Level::khz), baseline)
        }
    }
    val tableLayouts = rememberVoltageTableLayouts(tables, showPower)
    Column(modifier.fillMaxSize()) {
        ScreenHeading("电压计划") {
            TonalAction(
                TunerSymbol.REFRESH, "刷新", !busy,
                loading = state.operation == Operation.READING,
                onClick = onRefresh,
            )
            TonalAction(
                TunerSymbol.SHIELD, stringResource(R.string.compatibility_check), !busy,
                onClick = onCheckCompatibility,
            )
        }
        CdtToolbar(
            status = status,
            tone = tone,
            device = device,
            cdtState = cdtState,
            canEnable = state.cdtEnableBootId != null,
            canRestoreWithoutPlan = partition?.canRestore == true,
            cdtWritten = cdtDisk == "slt_enabled" || legacyCdt,
            cdtUncertain = state.cdtUncertain,
            busy = busy,
            onBackupCdt = onBackupCdt,
            onAskEnable = { confirmEnable = true },
            onAskRestore = { confirmRestore = true },
            onRebootNow = onRebootNow,
        )

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            val clusterSpacing = with(LocalDensity.current) { VoltageClusterSpacing.roundToPx() }
            val contentWidth = constraints.maxWidth
            val twoColumnWidth = (contentWidth - clusterSpacing) / 2
            val columnCount = if (tableLayouts.all { it.compactWidth <= twoColumnWidth }) 2 else 1
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                if (tables.isEmpty() && systemFrequencies.isEmpty() && !busy && state.error == null) {
                    item {
                        Text(
                            when {
                                partition?.standalone == true -> "当前电压计划不可用。"
                                state.benchmarkDevice?.voltagePlanSupported == false -> "当前内核缺少匹配的电压读表模块。"
                                else -> "当前电压计划不可用，点击右上角刷新。"
                            },
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                for ((domain, frequencies) in systemFrequencies.toSortedMap()) {
                    item(key = "system_$domain") {
                        Column(Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) {
                            Text("PDP$domain · 系统频点 MHz", style = MaterialTheme.typography.titleSmall)
                            Text(
                                frequencies.asReversed().joinToString(" · ") { decimal(it / 1000.0) },
                                style = MaterialTheme.typography.bodySmall.tabular(),
                            )
                        }
                    }
                }
                if (tables.isNotEmpty() && !showPower) {
                    item(key = "chart") {
                        VoltageChartCard(
                            tables = tables,
                            estimated = device?.estimatedScmiVoltage == true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
                        )
                    }
                }
                for (group in tableLayouts.chunked(columnCount)) {
                    val key = group.joinToString("_") { it.domain.toString() }
                    val tableWidth = if (group.size == 2) twoColumnWidth else contentWidth
                    val compact = group.any { it.fullWidth > tableWidth }
                    items(
                        count = group.maxOf { it.rows.size },
                        key = { row -> if (row == 0) "header_$key" else "level_${key}_${row - 1}" },
                    ) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VoltageClusterSpacing)) {
                            for (table in group) {
                                val values = table.rows.getOrNull(row)
                                if (values == null) {
                                    Spacer(Modifier.weight(1f))
                                } else {
                                    VoltageTableRow(
                                        table = table,
                                        values = values,
                                        row = row,
                                        compact = compact,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class VoltageTable(val domain: Int, val levels: List<Level>, val baseline: Map<Int, Level>)

private fun sm8850CdtState(device: DevicePlan): Pair<String, Tone> {
    if (device.cdtPendingReboot) return "CDT 已写 · 待重启" to Tone.WARNING
    val baseline = device.baseline
    if (device.baselineSource != BaselineSource.PERSISTED || baseline == null) {
        return "CDT 已写 · 效果未确认" to Tone.INFO
    }
    val deltas = device.current.zip(baseline).mapNotNull { (current, stock) ->
        val live = current.millivolts ?: return@mapNotNull null
        val original = stock.millivolts ?: return@mapNotNull null
        live - original
    }
    return when {
        deltas.any { it < 0 } && deltas.none { it > 0 } -> "CDT 已写 · 计划电压下降" to Tone.POSITIVE
        deltas.any { it != 0 } -> "CDT 已写 · 计划电压有变化" to Tone.INFO
        else -> "CDT 已写 · 计划电压无变化" to Tone.WARNING
    }
}

@Composable
private fun CdtToolbar(
    status: String,
    tone: Tone,
    device: DevicePlan?,
    cdtState: PlanState?,
    canEnable: Boolean,
    canRestoreWithoutPlan: Boolean,
    cdtWritten: Boolean,
    cdtUncertain: Boolean,
    busy: Boolean,
    onBackupCdt: () -> Unit,
    onAskEnable: () -> Unit,
    onAskRestore: () -> Unit,
    onRebootNow: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).width(IntrinsicSize.Max)) {
            StatusPill(status, tone)
        }
        if (!cdtWritten && !cdtUncertain) {
            FilledTonalButton(onClick = onBackupCdt, enabled = !busy) { Text("备份 CDT") }
        }
        val actionState = if (cdtState == PlanState.STOCK_UNMODIFIED &&
            device?.state == PlanState.EXTERNAL_RUNTIME
        ) PlanState.EXTERNAL_RUNTIME else cdtState
        when (actionState) {
            PlanState.STOCK_UNMODIFIED -> {
                if (canEnable) {
                    Button(onClick = onAskEnable, enabled = !busy) { Text("开启降压") }
                }
            }
            PlanState.CDT_WRITTEN ->
                FilledTonalButton(onClick = onAskRestore, enabled = !busy) { Text("还原原厂") }
            PlanState.CDT_RECOVERY_REQUIRED ->
                Button(onClick = onAskRestore, enabled = !busy) { Text("还原分区") }
            PlanState.EXTERNAL_RUNTIME -> {
                Button(onClick = onRebootNow, enabled = !busy) { Text("重启") }
                if (cdtWritten || canRestoreWithoutPlan || device?.hasCdtBackup == true) {
                    FilledTonalButton(onClick = onAskRestore, enabled = !busy) { Text("还原 CDT") }
                }
            }
            PlanState.CORRUPT_CDT, null -> {
                if (!cdtUncertain && canRestoreWithoutPlan) {
                    FilledTonalButton(onClick = onAskRestore, enabled = !busy) { Text("还原 CDT") }
                }
            }
        }
    }
}

@Preview(name = "Two columns", widthDp = 360, heightDp = 760, showBackground = true)
@Preview(name = "High DPI", widthDp = 270, heightDp = 600, showBackground = true)
@Preview(name = "Large text", widthDp = 200, heightDp = 650, fontScale = 2f, showBackground = true)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun VoltageScreenPreview() = VoltageScreenPreviewContent(showPower = false)

@Preview(name = "High DPI power table", widthDp = 270, heightDp = 600, showBackground = true)
@Composable
private fun VoltagePowerScreenPreview() = VoltageScreenPreviewContent(showPower = true)

@Composable
private fun VoltageScreenPreviewContent(showPower: Boolean) {
    fun levels(offsetMv: Int) = (0..1).flatMap { domain ->
        (0..4).map { index ->
            val khz = 600_000 + domain * 500_000 + index * (850_000 + domain * 50_000)
            val mv = 584 + domain * 40 + index * 96 + offsetMv
            val power = (100L * khz * mv * mv / 1_000_000_000).toInt()
            Level(domain, index, khz, power, if (showPower) null else 100)
        }
    }
    val state = ScreenState(
        plan = DevicePlan(
            bootId = "preview",
            state = PlanState.CDT_WRITTEN,
            cdtDisk = "slt_enabled",
            cdtKey7 = "enabled",
            hasCdtBackup = true,
            cdtPlatform = "",
            bootCdt = "",
            bootPlatformInfo = "",
            apnsSlot = "",
            baselineSource = if (showPower) BaselineSource.NONE else BaselineSource.PERSISTED,
            online = (0..7).toSet(),
            baseline = if (showPower) null else levels(24),
            current = levels(0),
            estimatedScmiVoltage = !showPower,
        ),
    )
    TunerTheme {
        Scaffold { padding ->
            VoltageScreen(
                state = state,
                onRefresh = {}, onCheckCompatibility = {}, onBackupCdt = {}, onEnableCdt = {}, onRestoreCdt = {},
                onRestoreCdtFromFile = {}, onRebootNow = {},
                modifier = Modifier.padding(padding),
            )
        }
    }
}

private data class VoltagePoint(val khz: Int, val mv: Int)

private data class VoltageTrace(val domain: Int, val original: Boolean, val points: List<VoltagePoint>)

@Composable
private fun voltageClusterColor(domain: Int): Color =
    if (domain == 0) MaterialTheme.colorScheme.primary else TunerThemeTokens.extended.warning

@Composable
private fun VoltageChartCard(
    tables: List<VoltageTable>,
    estimated: Boolean,
    modifier: Modifier = Modifier,
) {
    val traces = remember(tables) {
        fun points(levels: Collection<Level>) = levels.mapNotNull { level ->
            level.millivolts?.let { VoltagePoint(level.khz, it) }
        }.sortedBy(VoltagePoint::khz)
        tables.flatMap { table ->
            listOf(
                VoltageTrace(table.domain, original = false, points(table.levels)),
                VoltageTrace(table.domain, original = true, points(table.baseline.values)),
            )
        }.filter { it.points.isNotEmpty() }
    }
    if (traces.isEmpty()) return

    val chartBackground = MaterialTheme.colorScheme.surfaceContainerLow
    val colors = tables.associate { it.domain to voltageClusterColor(it.domain) }
    val traceColors = traces.associateWith { trace ->
        val tint = colors.getValue(trace.domain)
        if (trace.original) lerp(tint, chartBackground, 0.28f) else tint
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = chartBackground,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("频率与电压", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (estimated) Text("SCMI 估算", style = MaterialTheme.typography.labelSmall)
            }

            PlanChart(traceColors)

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for ((domain, series) in traces.groupBy(VoltageTrace::domain)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("PDP$domain", color = colors.getValue(domain), style = MaterialTheme.typography.labelMedium)
                        for (trace in series) {
                            ChartLegend(
                                if (trace.original) "原厂" else "当前",
                                traceColors.getValue(trace),
                                dashed = trace.original,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanChart(traceColors: Map<VoltageTrace, Color>) {
    val traces = remember(traceColors) { traceColors.keys.sortedBy(VoltageTrace::original) }
    val points = remember(traces) { traces.flatMap(VoltageTrace::points) }
    val lowKhz = points.minOf(VoltagePoint::khz)
    val highKhz = points.maxOf(VoltagePoint::khz)
    val lowMv = points.minOf(VoltagePoint::mv) / 50 * 50
    val highMv = ((points.maxOf(VoltagePoint::mv) + 49) / 50 * 50).coerceAtLeast(lowMv + 50)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.tabular()
    val chartHeight = 128.dp
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.height(chartHeight), verticalArrangement = Arrangement.SpaceBetween) {
            Text("${decimal(highMv / 1000.0, 2)} V", style = labelStyle)
            Text("${decimal(lowMv / 1000.0, 2)} V", style = labelStyle)
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(chartHeight).semantics {
                contentDescription = "各核心簇的频率与电压曲线"
            }) {
                val inset = 3.dp.toPx()
                val width = (size.width - 2 * inset).coerceAtLeast(0f)
                val height = (size.height - 2 * inset).coerceAtLeast(0f)
                fun position(point: VoltagePoint) = Offset(
                    inset + width * if (highKhz == lowKhz) 0.5f else (point.khz - lowKhz).toFloat() / (highKhz - lowKhz),
                    inset + height * (highMv - point.mv).toFloat() / (highMv - lowMv),
                )

                for (line in 0..3) {
                    val y = inset + height * line / 3
                    drawLine(gridColor, Offset(inset, y), Offset(inset + width, y), 1.dp.toPx())
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                for (trace in traces) {
                    val color = traceColors.getValue(trace)
                    val first = position(trace.points.first())
                    if (trace.points.size == 1) {
                        drawCircle(color, if (trace.original) 1.5.dp.toPx() else inset, first)
                        continue
                    }
                    val path = Path().apply {
                        moveTo(first.x, first.y)
                        for (index in 1 until trace.points.size) {
                            val point = position(trace.points[index])
                            lineTo(point.x, point.y)
                        }
                    }
                    drawPath(
                        path,
                        color,
                        style = Stroke(
                            width = if (trace.original) 1.75.dp.toPx() else 2.75.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = if (trace.original) dash else null,
                        ),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 3.dp),
                horizontalArrangement = if (highKhz == lowKhz) Arrangement.Center else Arrangement.SpaceBetween,
            ) {
                Text(ghz(lowKhz), style = labelStyle)
                if (highKhz != lowKhz) Text(ghz(highKhz), style = labelStyle)
            }
        }
    }
}

@Composable
private fun ChartLegend(label: String, tint: Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.width(18.dp).height(10.dp)) {
            drawLine(
                tint, Offset(0f, center.y), Offset(size.width, center.y),
                strokeWidth = if (dashed) 1.75.dp.toPx() else 2.75.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null,
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private val VoltageClusterSpacing = 6.dp
private val VoltageCellSpacing = 3.dp
private val VoltageCellPadding = 7.dp

private enum class VoltageColumn { FREQUENCY, ORIGINAL, CURRENT, DELTA }

private val PrimaryVoltageColumns = listOf(VoltageColumn.FREQUENCY, VoltageColumn.CURRENT)
private val SecondaryVoltageColumns = listOf(VoltageColumn.ORIGINAL, VoltageColumn.DELTA)

private data class VoltageRowValues(
    val frequency: String,
    val current: String,
    val original: String = "—",
    val deltaText: String = "—",
    val delta: Int? = null,
) {
    operator fun get(column: VoltageColumn): String = when (column) {
        VoltageColumn.FREQUENCY -> frequency
        VoltageColumn.ORIGINAL -> original
        VoltageColumn.CURRENT -> current
        VoltageColumn.DELTA -> deltaText
    }
}

private data class VoltageTableLayout(
    val domain: Int,
    val rows: List<VoltageRowValues>,
    val columnWidths: Map<VoltageColumn, Int>,
    val fullWidth: Int,
    val compactWidth: Int,
) {
    val fullLines = listOf(columnWidths.keys)
    val compactLines = if (VoltageColumn.ORIGINAL in columnWidths) {
        listOf(PrimaryVoltageColumns, SecondaryVoltageColumns)
    } else fullLines
}

private fun voltageTableRows(table: VoltageTable, showPower: Boolean): List<VoltageRowValues> = buildList {
    val heading = when {
        showPower -> "估算功耗 mW"
        table.baseline.isNotEmpty() -> "当前"
        else -> "电压 mV"
    }
    add(VoltageRowValues("MHz", heading, "原厂", "ΔmV"))
    for (level in table.levels) {
        val current = level.millivolts
        val original = table.baseline[level.khz]?.millivolts
        val delta = if (original != null && current != null) current - original else null
        add(
            VoltageRowValues(
                frequency = decimal(level.khz / 1000.0),
                current = if (showPower) decimal(level.microwatts / 1000.0) else current?.toString() ?: "—",
                original = original?.toString() ?: "—",
                deltaText = delta?.let { signed(it.toDouble(), 0) } ?: "—",
                delta = delta,
            ),
        )
    }
}

@Composable
private fun rememberVoltageTableLayouts(tables: List<VoltageTable>, showPower: Boolean): List<VoltageTableLayout> {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    return remember(tables, showPower, textMeasurer, density, typography) {
        val padding = with(density) { 2 * VoltageCellPadding.roundToPx() }
        val spacing = with(density) { VoltageCellSpacing.roundToPx() }
        val valueStyle = typography.bodySmall.tabular()
        val frequencyStyle = valueStyle.copy(fontWeight = FontWeight.SemiBold)
        fun width(text: String, style: TextStyle): Int =
            textMeasurer.measure(text, style = style, softWrap = false, maxLines = 1).size.width

        tables.map { table ->
            val comparison = table.baseline.isNotEmpty()
            val columns = if (comparison) VoltageColumn.entries else PrimaryVoltageColumns
            val rows = voltageTableRows(table, showPower)
            val header = rows.first()
            val values = rows.subList(1, rows.size)
            val widths = columns.associateWith { column ->
                val style = if (column == VoltageColumn.FREQUENCY) frequencyStyle else valueStyle
                // Unit labels can wrap at spaces when the column is narrow.
                val headerWidth = header[column].split(' ').maxOf { width(it, typography.labelMedium) }
                val valueWidth = values.asSequence().map { it[column] }.distinct().maxOf { width(it, style) }
                maxOf(headerWidth, valueWidth)
            }
            fun lineWidth(line: List<VoltageColumn>): Int =
                line.sumOf { widths.getValue(it) } + (line.size - 1) * spacing + padding

            val titleWidth = width("PDP${table.domain}", typography.titleSmall) + padding
            val fullWidth = maxOf(titleWidth, lineWidth(columns))
            val compactWidth = if (comparison) {
                maxOf(titleWidth, lineWidth(PrimaryVoltageColumns), lineWidth(SecondaryVoltageColumns))
            } else fullWidth
            VoltageTableLayout(table.domain, rows, widths, fullWidth, compactWidth)
        }
    }
}

@Composable
private fun VoltageTableRow(
    table: VoltageTableLayout,
    values: VoltageRowValues,
    row: Int,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val heading = row == 0
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val style = if (heading) typography.labelMedium else typography.bodySmall.tabular()
    val deltaColor = when {
        values.delta == null -> colors.onSurfaceVariant
        values.delta < 0 -> toneColors(Tone.POSITIVE).second
        values.delta > 0 -> toneColors(Tone.DANGER).second
        else -> toneColors(Tone.NEUTRAL).second
    }
    val lines = if (compact) table.compactLines else table.fullLines
    Column(modifier) {
        if (heading) {
            Text(
                "PDP${table.domain}",
                Modifier.padding(horizontal = VoltageCellPadding, vertical = 4.dp),
                color = voltageClusterColor(table.domain),
                style = typography.titleSmall,
            )
        }
        Surface(
            Modifier.fillMaxWidth(),
            shape = listSegmentShape(row, table.rows.size),
            color = if (heading) colors.surfaceContainerHigh else colors.surfaceContainerLow,
        ) {
            Column(Modifier.padding(horizontal = VoltageCellPadding, vertical = 4.dp)) {
                for (line in lines) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(VoltageCellSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for ((index, column) in line.withIndex()) {
                            Text(
                                values[column],
                                modifier = Modifier.weight(table.columnWidths.getValue(column).toFloat()),
                                style = style,
                                fontWeight = if (!heading && column == VoltageColumn.FREQUENCY) FontWeight.SemiBold else null,
                                textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                                color = when (column) {
                                    VoltageColumn.ORIGINAL -> colors.onSurfaceVariant
                                    VoltageColumn.DELTA -> deltaColor
                                    else -> Color.Unspecified
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun ghz(khz: Int): String = "${decimal(khz / 1_000_000.0, 2)} GHz"
