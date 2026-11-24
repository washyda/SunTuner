package io.washy.sm8750.tuner.ui

import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.washy.sm8750.tuner.BENCHMARK_DURATIONS_SECONDS
import io.washy.sm8750.tuner.BenchmarkDevice
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
    onDuration: (Int) -> Unit,
    onMode: (FrequencyMode) -> Unit,
    onWorkload: (WorkloadKind) -> Unit,
    onFrequency: (Int, Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state.operation == Operation.LOADING
    val online = state.benchmarkOnline
    val editable = state.operation == null && online.isNotEmpty()
    val config = state.config
    val context = LocalContext.current
    val batteryTemp = remember(context, state.operation) {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("烤机", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            if (batteryTemp != null) {
                Text(
                    "电池 ${decimal(batteryTemp / 10.0)} °C",
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "cores") {
                CorePicker(online, state.cores, editable, onCore, onPreset)
            }
            item(key = "settings") {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text("时长", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.End),
                            ) {
                                for (seconds in BENCHMARK_DURATIONS_SECONDS) {
                                    Choice("$seconds 秒", state.durationSeconds == seconds, editable,
                                        { onDuration(seconds) }, Modifier.widthIn(min = 60.dp))
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            val threadCount = state.threads.toIntOrNull() ?: state.cores.size.coerceAtLeast(1)
                            Text("线程", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Choice("−", false, editable && threadCount > state.cores.size.coerceAtLeast(1),
                                { onThreads((threadCount - 1).toString()) }, Modifier.width(48.dp), "减少线程")
                            ThreadCountField(state.threads, onThreads, editable, Modifier.width(48.dp))
                            Choice("+", false, editable && threadCount < 32,
                                { onThreads((threadCount + 1).toString()) }, Modifier.width(48.dp), "增加线程")
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text("调频", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.End),
                            ) {
                                for ((mode, label) in listOf(
                                    FrequencyMode.SYSTEM to "系统", FrequencyMode.MAX to "性能", FrequencyMode.FIXED to "锁频",
                                )) {
                                    Choice(label, mode == state.frequencyMode,
                                        editable && (mode != FrequencyMode.FIXED || state.benchmarkDevice?.fixedSupported != false),
                                        { onMode(mode) }, Modifier.widthIn(min = 60.dp))
                                }
                            }
                        }

                        if (state.frequencyMode == FrequencyMode.FIXED) {
                            for (domain in state.cores.map(::domainFor).distinct().sorted()) {
                                val levels = state.benchmarkFrequencies[domain].orEmpty()
                                if (levels.isNotEmpty()) {
                                    val target = config?.targets?.get(domain) ?: state.fixedFrequencies[domain] ?: levels.last()
                                    SettingsMenu("PDP$domain", ghz(target), levels.asReversed(), { level ->
                                        "${decimal(level / 1000.0)} MHz"
                                    }, editable) { onFrequency(domain, it) }
                                }
                            }
                        }

                        SettingsMenu("负载", state.workload.label, WorkloadKind.entries, { it.label }, editable, onWorkload)

                        Button(
                            onClick = if (running) onStop else onStart,
                            enabled = if (running) !state.loadStopping else editable && config != null && online.containsAll(state.cores),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            if (running) {
                                ExpressiveLoadingIndicator(Modifier.size(20.dp), color = LocalContentColor.current)
                            } else {
                                SymbolIcon(TunerSymbol.PLAY, Modifier.size(18.dp))
                            }
                            Text(
                                if (!running) "开始烤机" else if (state.loadStopping) "正在停止…" else "停止烤机",
                                Modifier.padding(start = 6.dp),
                            )
                        }
                        if (running) {
                            val total = state.durationSeconds
                            val elapsed = state.loadPhaseElapsedSec.coerceIn(0, total)
                            Text(
                                "$elapsed / $total 秒",
                                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 3.dp),
                                style = MaterialTheme.typography.labelMedium.tabular(),
                            )
                        }
                    }
                }
            }

            if (online.isEmpty() && !running) {
                item { Text("请先在电压页刷新设备信息。", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall) }
            }
            val run = state.lastRun
            if (run != null) {
                item {
                    val thermal = if (run.thermalStart != null && run.thermalEnd != null) {
                        "温控 ${run.thermalStart}→${run.thermalEnd}"
                    } else null
                    SectionHeader(title = "本次实测", subtitle = thermal)
                }
                item { RunKpiGrid(summary = run.summarizeStress(), run = run) }
                item { RunTechnicalDetails(run = run) }
            }
        }
    }
}

@Composable
private fun CorePicker(online: Set<Int>, selected: Set<Int>, editable: Boolean, onCore: (Int) -> Unit, onPreset: (Set<Int>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("核心", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box {
                    TextButton(
                        onClick = { expanded = true },
                        enabled = editable,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("预设")
                        SymbolIcon(TunerSymbol.DOWN, Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        for (preset in CORE_PRESETS) {
                            DropdownMenuItem(
                                text = { Text(preset.label) },
                                enabled = preset.cores.any { it in online },
                                onClick = { onPreset(preset.cores); expanded = false },
                            )
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 4.dp
                val capacity = ((maxWidth + gap) / (48.dp + gap)).toInt().coerceAtLeast(1)
                // At narrow widths, use three cores per row instead of leaving a single orphan.
                val columns = if (capacity >= 6) 6 else capacity.coerceAtMost(3)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((domain, cores) in listOf(0 to 0..5, 1 to 6..7)) {
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            Text(
                                "PDP$domain",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            for (row in cores.chunked(columns)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    for ((index, core) in row.withIndex()) {
                                        val start = if (index == 0) 12.dp else 4.dp
                                        val end = if (index == row.lastIndex) 12.dp else 4.dp
                                        CoreChoice(
                                            core = core,
                                            available = core in online,
                                            selected = core in selected && core in online,
                                            editable = editable,
                                            onClick = { onCore(core) },
                                            shape = RoundedCornerShape(start, end, end, start),
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
}

@Composable
private fun CoreChoice(
    core: Int,
    available: Boolean,
    selected: Boolean,
    editable: Boolean,
    onClick: () -> Unit,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = editable && available,
        modifier = modifier.semantics { contentDescription = if (available) "CPU$core" else "CPU$core 离线" },
        shape = shape,
        color = when {
            selected -> colors.primary
            available -> colors.surfaceContainerLowest
            else -> colors.surfaceContainer
        },
        contentColor = when {
            selected -> colors.onPrimary
            available -> colors.onSurface
            else -> colors.onSurfaceVariant
        },
        border = if (available && !selected) BorderStroke(1.dp, colors.outlineVariant) else null,
    ) {
        Column(
            modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                core.toString(),
                style = MaterialTheme.typography.labelLarge.tabular(),
                fontWeight = if (selected) FontWeight.SemiBold else null,
            )
            if (!available) Text("离线", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = label,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.primary else scheme.surfaceContainerHigh,
        contentColor = when {
            !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.52f)
            selected -> scheme.onPrimary
            else -> scheme.onSurface
        },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun <T> SettingsMenu(
    label: String,
    value: String,
    choices: List<T>,
    format: (T) -> String,
    enabled: Boolean,
    choose: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                SymbolIcon(TunerSymbol.DOWN)
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (choice in choices) {
                DropdownMenuItem(text = { Text(format(choice)) }, onClick = { choose(choice); expanded = false })
            }
        }
    }
}

@Composable
private fun ThreadCountField(value: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    var focused by remember { mutableStateOf(false) }
    val style = MaterialTheme.typography.labelLarge.tabular().copy(textAlign = TextAlign.Center)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "线程数" },
        enabled = enabled,
        singleLine = true,
        textStyle = style.copy(color = if (enabled) colors.onSurface else colors.onSurfaceVariant),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = SolidColor(colors.primary),
    ) { innerTextField ->
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .background(colors.surfaceContainer, shape)
                .border(1.dp, if (focused) colors.primary else colors.outlineVariant, shape)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (value.isEmpty()) Text("—", style = style, color = colors.onSurfaceVariant)
            Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) { innerTextField() }
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Preview(widthDp = 320, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun BenchmarkScreenPreview() {
    TunerTheme {
        BenchmarkScreen(
            state = ScreenState(
                benchmarkDevice = BenchmarkDevice("preview", (0..6).toSet(), mapOf(1 to listOf(4_320_000)), true),
                cores = setOf(0, 1, 6), threads = "3", durationSeconds = 30,
            ),
            onPreset = {}, onCore = {}, onThreads = {}, onDuration = {}, onMode = {},
            onWorkload = {}, onFrequency = { _, _ -> }, onStart = {}, onStop = {},
        )
    }
}
