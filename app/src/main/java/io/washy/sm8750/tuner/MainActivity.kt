package io.washy.sm8750.tuner

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.washy.sm8750.tuner.ui.BenchmarkScreen
import io.washy.sm8750.tuner.ui.StatusPill
import io.washy.sm8750.tuner.ui.TunerTheme
import io.washy.sm8750.tuner.ui.TunerThemeTokens
import io.washy.sm8750.tuner.ui.VoltageScreen
import io.washy.sm8750.tuner.ui.planStateTag

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[TunerViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.operation) {
                if (state.operation == Operation.LOADING || state.operation == Operation.WRITING_CDT) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            TunerTheme {
                TunerApp(
                    state = state,
                    onRefresh = model::refresh,
                    onEnableCdt = model::enableCdt,
                    onRestoreCdt = { model.restoreCdt(fallbackClean = false) },
                    onRestoreFallbackClean = { model.restoreCdt(fallbackClean = true) },
                    onDismissRestoreNoBackup = model::dismissRestoreNoBackup,
                    onRebootNow = model::rebootNow,
                    onDismissReboot = model::dismissRebootPrompt,
                    onPreset = model::applyPreset,
                    onCore = model::toggleCore,
                    onThreads = model::setThreads,
                    onSeconds = model::setSeconds,
                    onQuickSeconds = model::setQuickSeconds,
                    onMode = model::setFrequencyMode,
                    onWorkload = model::setWorkload,
                    onFrequency = model::setFixedFrequency,
                    onStart = model::startLoad,
                    onStop = model::stopLoad,
                )
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.stopLoad()
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunerApp(
    state: ScreenState,
    onRefresh: () -> Unit,
    onEnableCdt: () -> Unit,
    onRestoreCdt: () -> Unit,
    onRestoreFallbackClean: () -> Unit,
    onDismissRestoreNoBackup: () -> Unit,
    onRebootNow: () -> Unit,
    onDismissReboot: () -> Unit,
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
    var tab by rememberSaveable { mutableIntStateOf(0) }

    state.rebootPrompt?.let { prompt ->
        val title = if (prompt == RebootPrompt.ENABLED_CDT) "CDT 写入成功" else "CDT 还原成功"
        val body = if (prompt == RebootPrompt.ENABLED_CDT) {
            "分区校验通过，需要重启生效。"
        } else {
            "分区已还原并校验，需要重启恢复原厂电压。"
        }
        AlertDialog(
            onDismissRequest = onDismissReboot,
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { Button(onClick = onRebootNow) { Text("立即重启") } },
            dismissButton = { TextButton(onClick = onDismissReboot) { Text("稍后重启") } },
        )
    }

    if (state.pendingRestoreNoBackup) {
        AlertDialog(
            onDismissRequest = onDismissRestoreNoBackup,
            title = { Text("未找到原厂备份") },
            text = { Text("该机在安装本应用前已含 Key 7。是否直接移除 Key 7（或还原空分区）？") },
            confirmButton = { Button(onClick = onRestoreFallbackClean) { Text("确认清理") } },
            dismissButton = { TextButton(onClick = onDismissRestoreNoBackup) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            if (tab == 1) {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        state.plan?.let { device ->
                            val (label, tone) = planStateTag(device.state)
                            StatusPill(label, tone, Modifier.padding(end = 4.dp))
                        }
                        TextButton(enabled = state.operation == null, onClick = onRefresh) { Text("刷新") }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { VoltageNavIcon() },
                    label = { Text("电压") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { BenchmarkNavIcon() },
                    label = { Text("烤机") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.operation != null && state.operation != Operation.LOADING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.operation != null && state.operation != Operation.LOADING && state.status != null) {
                Text(
                    state.status,
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let { err ->
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    color = TunerThemeTokens.extended.dangerContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        err,
                        Modifier.padding(12.dp),
                        color = TunerThemeTokens.extended.danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                if (tab == 0) {
                    VoltageScreen(
                        state = state,
                        onRefresh = onRefresh,
                        onEnableCdt = onEnableCdt,
                        onRestoreCdt = onRestoreCdt,
                        onRebootNow = onRebootNow,
                    )
                } else {
                    BenchmarkScreen(
                        state = state,
                        onPreset = onPreset,
                        onCore = onCore,
                        onThreads = onThreads,
                        onSeconds = onSeconds,
                        onQuickSeconds = onQuickSeconds,
                        onMode = onMode,
                        onWorkload = onWorkload,
                        onFrequency = onFrequency,
                        onStart = onStart,
                        onStop = onStop,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoltageNavIcon() {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.56f, h * 0.08f)
            lineTo(w * 0.22f, h * 0.54f)
            lineTo(w * 0.48f, h * 0.54f)
            lineTo(w * 0.42f, h * 0.92f)
            lineTo(w * 0.78f, h * 0.46f)
            lineTo(w * 0.52f, h * 0.46f)
            close()
        }
        drawPath(path, color = tint, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun BenchmarkNavIcon() {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.22f, h * 0.22f),
            size = Size(w * 0.56f, h * 0.56f),
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
            style = stroke,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.37f, h * 0.37f),
            size = Size(w * 0.26f, h * 0.26f),
            cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
            style = stroke,
        )
        val pins = listOf(0.36f, 0.50f, 0.64f)
        pins.forEach { p ->
            drawLine(tint, Offset(w * p, h * 0.08f), Offset(w * p, h * 0.22f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * p, h * 0.78f), Offset(w * p, h * 0.92f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.08f, h * p), Offset(w * 0.22f, h * p), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.78f, h * p), Offset(w * 0.92f, h * p), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TunerPreview() {
    TunerTheme {
        TunerApp(
            state = ScreenState(),
            onRefresh = {}, onEnableCdt = {}, onRestoreCdt = {}, onRestoreFallbackClean = {},
            onDismissRestoreNoBackup = {}, onRebootNow = {}, onDismissReboot = {},
            onPreset = {}, onCore = {}, onThreads = {}, onSeconds = {}, onQuickSeconds = {},
            onMode = {}, onWorkload = {}, onFrequency = { _, _ -> }, onStart = {}, onStop = {},
        )
    }
}
