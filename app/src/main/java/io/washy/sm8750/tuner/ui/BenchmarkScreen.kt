package io.washy.sm8750.tuner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.washy.sm8750.tuner.CORE_PRESETS
import io.washy.sm8750.tuner.FrequencyMode
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
    val running = state.operation == Operation.LOADING
    val editable = state.operation == null && state.benchmarkOnline.isNotEmpty()
    val config = state.config
    var showCustomCores by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                                val available = preset.cores.intersect(state.benchmarkOnline)
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
                                            enabled = editable && core in state.benchmarkOnline,
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
                                val levels = state.benchmarkFrequencies[domain].orEmpty()
                                if (levels.isNotEmpty()) {
                                    FrequencyPicker(
                                        domain,
                                        config?.targets?.get(domain) ?: state.fixedFrequencies[domain] ?: levels.last(),
                                        levels,
                                        editable,
                                        onFrequency,
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = if (running) onStop else onStart,
                        enabled = if (running) !state.loadStopping else editable && config != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (running) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = LocalContentColor.current,
                                    strokeWidth = 2.dp,
                                )
                                Text(if (state.loadStopping) "正在停止…" else "停止烤机")
                            }
                        } else {
                            Text("开始烤机")
                        }
                    }
                    if (running) {
                        val total = state.seconds.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val elapsed = state.loadPhaseElapsedSec.coerceIn(0, total)
                        if (elapsed == 0 && !state.loadStopping) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { elapsed.toFloat() / total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            "${elapsed}s / ${total}s",
                            modifier = Modifier.align(Alignment.End),
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
        } else if (!running) {
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
private fun FrequencyPicker(domain: Int, selected: Int, levels: List<Int>, enabled: Boolean, choose: (Int, Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }) {
            Text("PDP$domain ${decimal(selected / 1000.0, 0)}MHz ▾", style = MaterialTheme.typography.labelMedium.tabular())
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (level in levels.asReversed()) {
                DropdownMenuItem(
                    text = { Text("${decimal(level / 1000.0)} MHz", style = MaterialTheme.typography.bodyMedium.tabular()) },
                    onClick = { choose(domain, level); expanded = false },
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
