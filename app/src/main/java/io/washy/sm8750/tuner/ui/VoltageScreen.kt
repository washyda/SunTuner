package io.washy.sm8750.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import io.washy.sm8750.tuner.BaselineSource
import io.washy.sm8750.tuner.DevicePlan
import io.washy.sm8750.tuner.Level
import io.washy.sm8750.tuner.PlanState
import io.washy.sm8750.tuner.ScreenState

@Composable
internal fun VoltageScreen(
    state: ScreenState,
    onRefresh: () -> Unit,
    onEnableCdt: () -> Unit,
    onRestoreCdt: () -> Unit,
    onRebootNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = state.plan
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
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("还原原厂电压") },
            text = { Text("将恢复 CDT 分区并校验，重启后回到原厂电压。") },
            confirmButton = { Button(onClick = { confirmRestore = false; onRestoreCdt() }) { Text("还原 CDT") } },
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
                busy = state.operation != null,
                onRefresh = onRefresh,
                onAskEnable = { confirmEnable = true },
                onAskRestore = { confirmRestore = true },
                onRebootNow = onRebootNow,
            )
        }
        if (device == null) return@LazyColumn

        item { HardwareStatusRow(device) }

        val showComparison = device.state == PlanState.CDT_ACTIVE ||
            device.state == PlanState.RESTORE_PENDING_REBOOT ||
            device.state == PlanState.EXTERNAL_RUNTIME

        if (showComparison && device.baseline == null) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = TunerThemeTokens.extended.infoContainer) {
                    Text(
                        "开启前未在本应用采样过原厂表，基线列显示 —；还原重启即可采样原厂基线。",
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("32 档电压计划", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = clusterFilter == -1, onClick = { clusterFilter = -1 }, label = { Text("全部") })
                    FilterChip(selected = clusterFilter == 0, onClick = { clusterFilter = 0 }, label = { Text("小/中核") })
                    FilterChip(selected = clusterFilter == 1, onClick = { clusterFilter = 1 }, label = { Text("超大核") })
                }
            }
        }

        val visibleDomains = if (clusterFilter in 0..1) listOf(clusterFilter) else listOf(0, 1)
        for (domain in visibleDomains) {
            item(key = "cluster_card_$domain") {
                ClusterVoltageCard(
                    domain = domain,
                    currentLevels = device.current.filter { it.domain == domain },
                    baselineLevels = device.baseline?.filter { it.domain == domain },
                    showComparison = showComparison,
                )
            }
        }
    }
}

@Composable
private fun CdtActions(
    device: DevicePlan?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onAskEnable: () -> Unit,
    onAskRestore: () -> Unit,
    onRebootNow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        device?.let {
            val (label, tone) = planStateTag(it.state)
            StatusPill(label, tone)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (device?.state) {
                PlanState.STOCK_UNMODIFIED ->
                    Button(onClick = onAskEnable, enabled = !busy) { Text("开启降压") }
                PlanState.CDT_PENDING_REBOOT -> {
                    Button(onClick = onRebootNow, enabled = !busy) { Text("立即重启") }
                    OutlinedButton(onClick = onAskRestore, enabled = !busy) { Text("撤销") }
                }
                PlanState.CDT_ACTIVE ->
                    OutlinedButton(onClick = onAskRestore, enabled = !busy) { Text("还原原厂") }
                PlanState.RESTORE_PENDING_REBOOT -> {
                    Button(onClick = onRebootNow, enabled = !busy) { Text("立即重启") }
                    OutlinedButton(onClick = onAskEnable, enabled = !busy) { Text("重新开启") }
                }
                PlanState.EXTERNAL_RUNTIME -> {
                    if (device.cdtDisk == "slt_enabled" || device.hasCdtBackup) {
                        OutlinedButton(onClick = onAskRestore, enabled = !busy) { Text("还原 CDT") }
                    }
                    Button(onClick = onRebootNow, enabled = !busy) { Text("重启") }
                }
                PlanState.CORRUPT_CDT, null -> Unit
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
            onRefresh = {}, onEnableCdt = {}, onRestoreCdt = {}, onRebootNow = {},
        )
    }
}

@Composable
private fun HardwareStatusRow(device: DevicePlan) {
    val diskLabel = when (device.cdtDisk) {
        "slt_enabled" -> "Key7=1"
        "empty" -> "原厂空白"
        "stock" -> "原厂"
        else -> device.cdtDisk
    }
    val bootLabel = when (device.bootCdt) {
        "applied" -> "已加载"
        "not_found" -> "未启用"
        else -> device.bootCdt
    }
    val baseLabel = when (device.baselineSource) {
        BaselineSource.CURRENT_STOCK -> "实时原厂"
        BaselineSource.PERSISTED -> "已存档"
        BaselineSource.NONE -> "无"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyValuePill("CDT 分区", diskLabel, if (device.cdtDisk == "slt_enabled") Tone.POSITIVE else Tone.NEUTRAL, Modifier.weight(1f))
        KeyValuePill("冷启动", bootLabel, if (device.bootCdt == "applied") Tone.POSITIVE else Tone.NEUTRAL, Modifier.weight(1f))
        KeyValuePill("原厂备份", if (device.hasCdtBackup) "已保留" else "无", if (device.hasCdtBackup) Tone.INFO else Tone.NEUTRAL, Modifier.weight(1f))
        KeyValuePill("基线", baseLabel, Tone.NEUTRAL, Modifier.weight(1f))
    }
}

@Composable
private fun ClusterVoltageCard(
    domain: Int,
    currentLevels: List<Level>,
    baselineLevels: List<Level>?,
    showComparison: Boolean,
) {
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
