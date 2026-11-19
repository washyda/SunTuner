package io.washy.sm8750.tuner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import io.washy.sm8750.tuner.BaselineSource
import io.washy.sm8750.tuner.DevicePlan
import io.washy.sm8750.tuner.Level
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
    onRebootNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = state.plan
    val partition = state.cdtPartition
    val cdtState = when {
        state.cdtUncertain -> null
        partition != null -> partition.state
        else -> device?.state
    }
    var confirmEnable by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    var clusterFilter by rememberSaveable { mutableIntStateOf(-1) }

    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("开启降压") },
            text = { Text("将先备份原厂分区，再写入 CDT 降压标志并校验，重启后生效。") },
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
                Text(
                    if (recoveryRequired) "当前分区与原厂备份不一致。将写回原厂备份并校验整个分区。"
                    else "将恢复 CDT 分区并校验，重启后回到原厂电压。"
                )
            },
            confirmButton = { Button(onClick = { confirmRestore = false; onRestoreCdt() }) { Text("还原分区") } },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } },
        )
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CdtActions(
                device = device,
                cdtState = cdtState,
                canRestoreWithoutPlan = partition?.canRestore == true,
                cdtWritten = (partition?.disk ?: device?.cdtDisk) == "slt_enabled",
                cdtUncertain = state.cdtUncertain,
                busy = state.operation != null,
                onRefresh = onRefresh,
                onCheckCompatibility = onCheckCompatibility,
                onBackupCdt = onBackupCdt,
                onAskEnable = { confirmEnable = true },
                onAskRestore = { confirmRestore = true },
                onRebootNow = onRebootNow,
            )
        }
        if (device == null) {
            if (cdtState != null && state.operation == null) {
                item {
                    Text(
                        "当前电压表读取失败，可刷新重试。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }

        item(key = "voltage_plan_section") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "当前电压计划",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                VoltageClusterSelect(
                    selectedCluster = clusterFilter,
                    onSelect = { clusterFilter = it },
                )
            }
        }

        val comparisonBaseline = device.baseline.takeIf {
            device.baselineSource == BaselineSource.PERSISTED &&
                (device.state == PlanState.CDT_WRITTEN || device.state == PlanState.EXTERNAL_RUNTIME)
        }

        val visibleDomains = if (clusterFilter in 0..1) listOf(clusterFilter) else listOf(0, 1)
        for (domain in visibleDomains) {
            item(key = "cluster_card_$domain") {
                ClusterVoltageCard(
                    domain = domain,
                    currentLevels = device.current.filter { it.domain == domain },
                    baselineLevels = comparisonBaseline?.filter { it.domain == domain }
                        ?.takeIf { levels -> levels.any { it.millivolts != null } },
                )
            }
        }
    }
}

@Composable
private fun VoltageClusterSelect(selectedCluster: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(-1 to "全部", 0 to "小/中核", 1 to "超大核")
    val selectedLabel = options.firstOrNull { it.first == selectedCluster }?.second ?: options.first().second

    Box {
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.semantics {
                stateDescription = if (expanded) "已展开" else "已收起"
            },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selectedLabel)
                val tint = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.size(16.dp).rotate(if (expanded) 180f else 0f)) {
                    val start = Offset(size.width * 0.25f, size.height * 0.4f)
                    val center = Offset(size.width * 0.5f, size.height * 0.65f)
                    val end = Offset(size.width * 0.75f, size.height * 0.4f)
                    drawLine(tint, start, center, strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, center, end, strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((cluster, label) in options) {
                val isSelected = cluster == selectedCluster
                DropdownMenuItem(
                    text = { Text(label, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        onSelect(cluster)
                        expanded = false
                    },
                    modifier = Modifier.semantics { selected = isSelected },
                )
            }
        }
    }
}

@Composable
private fun CdtActions(
    device: DevicePlan?,
    cdtState: PlanState?,
    canRestoreWithoutPlan: Boolean,
    cdtWritten: Boolean,
    cdtUncertain: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    onCheckCompatibility: () -> Unit,
    onBackupCdt: () -> Unit,
    onAskEnable: () -> Unit,
    onAskRestore: () -> Unit,
    onRebootNow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (cdtState == null) {
                    if (!busy || cdtUncertain) StatusPill("CDT 状态待确认", Tone.WARNING)
                } else {
                    val (label, tone) = planStateTag(cdtState)
                    StatusPill(label, tone)
                }
            }
            TextButton(onClick = onCheckCompatibility, enabled = !busy) { Text(stringResource(R.string.compatibility_check)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val actionState = if (cdtState == PlanState.STOCK_UNMODIFIED &&
                device?.state == PlanState.EXTERNAL_RUNTIME
            ) PlanState.EXTERNAL_RUNTIME else cdtState
            when (actionState) {
                PlanState.STOCK_UNMODIFIED -> {
                    if (device?.state == PlanState.STOCK_UNMODIFIED) {
                        Button(onClick = onAskEnable, enabled = !busy) { Text("开启降压") }
                    }
                }
                PlanState.CDT_WRITTEN ->
                    OutlinedButton(onClick = onAskRestore, enabled = !busy) { Text("还原原厂") }
                PlanState.CDT_RECOVERY_REQUIRED ->
                    Button(onClick = onAskRestore, enabled = !busy) { Text("还原分区") }
                PlanState.EXTERNAL_RUNTIME -> {
                    if (cdtWritten || canRestoreWithoutPlan || device?.hasCdtBackup == true) {
                        OutlinedButton(onClick = onAskRestore, enabled = !busy) { Text("还原 CDT") }
                    }
                    Button(onClick = onRebootNow, enabled = !busy) { Text("重启") }
                }
                PlanState.CORRUPT_CDT, null -> {
                    if (!cdtUncertain && canRestoreWithoutPlan) {
                        OutlinedButton(onClick = onAskRestore, enabled = !busy) { Text("还原 CDT") }
                    }
                }
            }
            if (!cdtWritten && !cdtUncertain) {
                OutlinedButton(onClick = onBackupCdt, enabled = !busy) { Text("备份 CDT") }
            }
            TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun VoltageScreenPreview() {
    TunerTheme {
        VoltageScreen(
            state = ScreenState(),
            onRefresh = {}, onCheckCompatibility = {}, onBackupCdt = {}, onEnableCdt = {}, onRestoreCdt = {}, onRebootNow = {},
        )
    }
}

@Composable
private fun ClusterVoltageCard(
    domain: Int,
    currentLevels: List<Level>,
    baselineLevels: List<Level>?,
) {
    val showComparison = baselineLevels != null
    val maxMv = (currentLevels.mapNotNull { it.millivolts } + baselineLevels.orEmpty().mapNotNull { it.millivolts })
        .maxOrNull()?.coerceAtLeast(900) ?: 1000
    val title = if (domain == 0) "PDP0 · CPU0–5" else "PDP1 · CPU6–7"

    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                if (currentLevels.isNotEmpty()) {
                    Text(
                        "${decimal(currentLevels.first().khz / 1000.0, 0)}–${decimal(currentLevels.last().khz / 1000.0, 0)} MHz",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("频点", Modifier.weight(1.25f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (showComparison) "原厂" else "电压", Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                if (showComparison) {
                    Text("当前", Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                    Text("变化", Modifier.weight(0.85f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            currentLevels.forEach { level ->
                val curMv = level.millivolts
                val baseMv = baselineLevels?.getOrNull(level.index)?.millivolts
                val delta = if (showComparison && baseMv != null && curMv != null) curMv - baseMv else null
                val barFraction = ((curMv ?: baseMv ?: 0) / maxMv.toFloat()).coerceIn(0f, 1f)
                val barColor = if (delta != null && delta < 0) TunerThemeTokens.extended.positive else MaterialTheme.colorScheme.primary

                Column(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${decimal(level.khz / 1000.0)} MHz", Modifier.weight(1.25f), style = MaterialTheme.typography.bodyMedium.tabular())
                        Text((if (showComparison) baseMv else curMv)?.let { "$it mV" } ?: "—", Modifier.weight(0.9f), style = MaterialTheme.typography.bodyMedium.tabular(), textAlign = TextAlign.End)
                        if (showComparison) {
                            Text(curMv?.let { "$it mV" } ?: "—", Modifier.weight(0.9f), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold).tabular(), textAlign = TextAlign.End)
                            val tone = when {
                                delta == null || delta == 0 -> Tone.NEUTRAL
                                delta < 0 -> Tone.POSITIVE
                                else -> Tone.DANGER
                            }
                            Text(
                                delta?.let { "${signed(it.toDouble(), 0)} mV" } ?: "—",
                                Modifier.weight(0.85f),
                                color = toneColors(tone).second,
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Box(Modifier.fillMaxWidth(barFraction).height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(barColor.copy(alpha = 0.65f)))
                    }
                }
            }
        }
    }
}
