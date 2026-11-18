package io.washy.sm8750.tuner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.washy.sm8750.tuner.CORE_PRESETS
import io.washy.sm8750.tuner.FrequencyMode
import io.washy.sm8750.tuner.Level
import io.washy.sm8750.tuner.LiveFreqSample
import io.washy.sm8750.tuner.Operation
import io.washy.sm8750.tuner.ScreenState
import io.washy.sm8750.tuner.WorkloadKind
import io.washy.sm8750.tuner.domainFor

@Composable
internal fun BenchmarkScreen(
    state: ScreenState,
    onPreset: (Set<Int>) -> Unit,
    onCore: (Int) -> Unit,
    onThreads: (String) -> Unit,
    onSeconds: (String) -> Unit,
    onQuickSeconds: (Int) -> Unit,
    onMode: (FrequencyMode) -> Unit,
    onWorkload: (WorkloadKind) -> Unit,
    onFrequency: (Int, Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val editable = state.operation == null && state.plan != null
    val config = state.config
    var showCustomCores by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.operation == Operation.LOADING && config != null) {
            item {
                LiveMonitorCard(
                    status = state.status ?: "压测运行中…",
                    elapsedSec = state.loadPhaseElapsedSec,
                    totalSec = config.seconds,
                    liveFrequencies = state.liveFrequencies,
                    targets = config.targets,
                    onStop = onStop,
                )
            }
        }

        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("烤机设置", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        TextButton(onClick = { showCustomCores = !showCustomCores }) {
                            Text(if (showCustomCores) "收起自选 ▲" else "自选核心 (${state.cores.size}核) ▾")
                        }
                    }

                    CORE_PRESETS.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { preset ->
                                val available = preset.cores.intersect(state.plan?.online.orEmpty())
                                FilterChip(
                                    selected = available.isNotEmpty() && state.cores == available,
                                    onClick = { onPreset(preset.cores) },
                                    enabled = editable && available.isNotEmpty(),
                                    label = { Text(preset.label) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = showCustomCores) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            (0..7).chunked(4).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { core ->
                                        FilterChip(
                                            selected = core in state.cores,
                                            onClick = { onCore(core) },
                                            enabled = editable && core in state.plan.online,
                                            label = { Text("C$core") },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(8, 15, 25).forEach { sec ->
                            FilterChip(
                                selected = state.seconds == sec.toString(),
                                onClick = { onQuickSeconds(sec) },
                                enabled = editable,
                                label = { Text("${sec}s") },
                            )
                        }
                        NumberField("线程", state.threads, onThreads, editable, Modifier.weight(1f))
                        NumberField("秒数", state.seconds, onSeconds, editable, Modifier.weight(1f))
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkloadKind.entries.forEach { kind ->
                            FilterChip(
                                selected = kind == state.workload,
                                enabled = editable,
                                onClick = { onWorkload(kind) },
                                label = { Text(kind.label) },
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FrequencyMode.entries.forEach { mode ->
                            FilterChip(
                                selected = mode == state.frequencyMode,
                                enabled = editable,
                                onClick = { onMode(mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }

                    if (state.frequencyMode == FrequencyMode.FIXED) {
                        val domains = state.cores.map(::domainFor).distinct().sorted()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            domains.forEach { domain ->
                                val levels = state.plan?.current.orEmpty().filter { it.domain == domain }
                                if (levels.isNotEmpty()) {
                                    FrequencyPicker(
                                        domain,
                                        config?.targets?.get(domain) ?: state.fixedFrequencies[domain] ?: levels.last().khz,
                                        levels,
                                        editable,
                                        onFrequency,
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = onStart,
                        enabled = editable && config != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("开始烤机") }
                }
            }
        }

        val run = state.lastRun
        if (run != null) {
            item {
                val thText = if (run.thermalStart != null && run.thermalEnd != null) "温控 ${run.thermalStart}→${run.thermalEnd}" else null
                SectionHeader(title = "本次实测", subtitle = thText)
            }
            item {
                RunKpiGrid(summary = run.summarizeStress(), run = run)
            }
            item {
                RunTechnicalDetails(run = run)
            }
        } else {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(
                        "选择核心与负载类型后点击「开始烤机」，即可实测 INT/FP 算力、PMU 主频与 PMIC PVC 电压。",
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveMonitorCard(
    status: String,
    elapsedSec: Int,
    totalSec: Int,
    liveFrequencies: Map<Int, LiveFreqSample>,
    targets: Map<Int, Int>,
    onStop: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("${elapsedSec}s / ${totalSec}s", style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (elapsedSec > 0) {
                LinearProgressIndicator(
                    progress = { (elapsedSec.toFloat() / totalSec.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
            }
            liveFrequencies.toSortedMap().values.forEach { sample ->
                val target = targets[sample.domain]
                val minimum = sample.effectiveMinKhz
                val maximum = sample.effectiveMaxKhz
                val current = sample.currentKhz.takeIf { it > 0 }?.let { mhzLabel(it) } ?: "—"
                val range = if (minimum > 0 && maximum > 0) frequencyRangeLabel(minimum, maximum) else "—"
                val targetText = target?.let { " · 目标 ${mhzLabel(it)}" }.orEmpty()
                val constrained = target != null && (minimum != target || maximum != target)
                val stateText = if (target != null && maximum in 1 until target) " · 上限受限"
                    else if (constrained) " · 锁频未生效" else ""
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("PDP${sample.domain} · 当前 $current", style = MaterialTheme.typography.labelMedium.tabular())
                        Text(
                            "有效 $range$targetText$stateText",
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = if (constrained) TunerThemeTokens.extended.warning else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止烤机") }
        }
    }
}

@Composable
private fun FrequencyPicker(domain: Int, selected: Int, levels: List<Level>, enabled: Boolean, choose: (Int, Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }) {
            Text("PDP$domain ${decimal(selected / 1000.0, 0)}MHz ▾", style = MaterialTheme.typography.labelMedium.tabular())
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (level in levels.asReversed()) {
                DropdownMenuItem(
                    text = { Text("${decimal(level.khz / 1000.0)} MHz", style = MaterialTheme.typography.bodyMedium.tabular()) },
                    onClick = { choose(domain, level.khz); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value, onChange, modifier, enabled = enabled, singleLine = true, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
