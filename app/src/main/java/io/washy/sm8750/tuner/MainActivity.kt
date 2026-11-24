package io.washy.sm8750.tuner

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.washy.sm8750.tuner.ui.BenchmarkScreen
import io.washy.sm8750.tuner.ui.CompatibilityScreen
import io.washy.sm8750.tuner.ui.CrashLogScreen
import io.washy.sm8750.tuner.ui.ExpressiveLoadingIndicator
import io.washy.sm8750.tuner.ui.TunerTheme
import io.washy.sm8750.tuner.ui.TunerThemeTokens
import io.washy.sm8750.tuner.ui.VoltageScreen

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[TunerViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val diagnosticsPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri -> uri?.let(model::exportDiagnostics) }
            val cdtLogPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/plain"),
            ) { uri -> uri?.let(model::exportCdtFailureLog) }
            val cdtBackupPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri -> uri?.let(model::backupCdt) }
            val cdtRestorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { model.restoreCdt(source = it) }
            }
            LaunchedEffect(state.operation) {
                if (state.operation == Operation.LOADING ||
                    state.operation == Operation.WRITING_CDT
                ) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            TunerTheme {
                TunerApp(
                    state = state,
                    onRefresh = model::refresh,
                    onCheckCompatibility = model::checkCompatibility,
                    onExportDiagnostics = {
                        val modelName = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
                        diagnosticsPicker.launch("SunTuner-$modelName-diagnostics.zip")
                    },
                    onDismissDiagnosticsResult = model::dismissDiagnosticsResult,
                    onExportCdtFailureLog = {
                        val modelName = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
                        cdtLogPicker.launch("SunTuner-$modelName-CDT-failure.txt")
                    },
                    onDismissCdtLogExportResult = model::dismissCdtLogExportResult,
                    onBackupCdt = {
                        val modelName = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
                        cdtBackupPicker.launch("SunTuner-$modelName-CDT.img")
                    },
                    onDismissCdtBackupResult = model::dismissCdtBackupResult,
                    onEnableCdt = model::enableCdt,
                    onRestoreCdt = { model.restoreCdt(fallbackClean = false) },
                    onRestoreCdtFromFile = { cdtRestorePicker.launch(arrayOf("*/*")) },
                    onRestoreFallbackClean = { model.restoreCdt(fallbackClean = true) },
                    onDismissRestoreNoBackup = model::dismissRestoreNoBackup,
                    onRebootNow = model::rebootNow,
                    onDismissReboot = model::dismissRebootPrompt,
                    onPreset = model::applyPreset,
                    onCore = model::toggleCore,
                    onThreads = model::setThreads,
                    onDuration = model::setDuration,
                    onMode = model::setFrequencyMode,
                    onWorkload = model::setWorkload,
                    onFrequency = model::setFixedFrequency,
                    onStart = model::startLoad,
                    onStop = model::stopLoad,
                    crashLogContent = {
                        val logs by model.crashLogState.collectAsStateWithLifecycle()
                        LaunchedEffect(Unit) { model.refreshCrashLogs() }
                        CrashLogScreen(logs, model::refreshCrashLogs, model::deleteCrashLogs, model::exportCrashLog)
                    },
                )
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.stopLoad()
        super.onStop()
    }
}

@Composable
private fun TunerApp(
    state: ScreenState,
    onRefresh: () -> Unit,
    onCheckCompatibility: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onDismissDiagnosticsResult: () -> Unit,
    onExportCdtFailureLog: () -> Unit,
    onDismissCdtLogExportResult: () -> Unit,
    onBackupCdt: () -> Unit,
    onDismissCdtBackupResult: () -> Unit,
    onEnableCdt: () -> Unit,
    onRestoreCdt: () -> Unit,
    onRestoreCdtFromFile: () -> Unit,
    onRestoreFallbackClean: () -> Unit,
    onDismissRestoreNoBackup: () -> Unit,
    onRebootNow: () -> Unit,
    onDismissReboot: () -> Unit,
    onPreset: (Set<Int>) -> Unit,
    onCore: (Int) -> Unit,
    onThreads: (String) -> Unit,
    onDuration: (Int) -> Unit,
    onMode: (FrequencyMode) -> Unit,
    onWorkload: (WorkloadKind) -> Unit,
    onFrequency: (Int, Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    crashLogContent: @Composable () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    state.compatibilityReport?.let { report ->
        CompatibilityScreen(
            report = report,
            saving = state.operation != null,
            missingFiles = state.diagnosticMissingFiles,
            error = state.error,
            onSave = onExportDiagnostics,
            onBack = onDismissDiagnosticsResult,
        )
        return
    }

    if (state.cdtLogExportComplete) {
        AlertDialog(
            onDismissRequest = onDismissCdtLogExportResult,
            title = { Text("刷入日志已保存") },
            text = { Text("请把保存的日志文件发给开发者排查。") },
            confirmButton = { TextButton(onClick = onDismissCdtLogExportResult) { Text("完成") } },
        )
    }

    state.cdtBackupResult?.let { result ->
        AlertDialog(
            onDismissRequest = onDismissCdtBackupResult,
            title = { Text("CDT 分区已备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前 CDT 完整镜像已保存到所选位置。")
                    result.displayName?.let { Text(it, fontWeight = FontWeight.Bold) }
                    Text("SHA-256:\n${result.sha256}", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = onDismissCdtBackupResult) { Text("完成") } },
        )
    }

    state.rebootPrompt?.let { prompt ->
        val title = if (prompt == RebootPrompt.ENABLED_CDT) "CDT 写入成功" else "CDT 还原成功"
        val body = if (prompt == RebootPrompt.ENABLED_CDT) {
            "分区校验通过。重启后可检查电压计划是否变化。"
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
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
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
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { CrashLogNavIcon() },
                    label = { Text("日志") },
                )
            }
        },
    ) { padding ->
        if (tab == 2) {
            Box(Modifier.fillMaxSize().padding(padding)) { crashLogContent() }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                state.error?.let { err ->
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        color = TunerThemeTokens.extended.dangerContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                err,
                                color = TunerThemeTokens.extended.danger,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (state.cdtFailureFeedback) {
                                TextButton(
                                    onClick = onExportCdtFailureLog,
                                    enabled = state.operation == null,
                                ) { Text("导出完整刷入日志") }
                            }
                        }
                    }
                }
                if (tab == 0 && state.hasCdtFailureLog && !state.cdtFailureFeedback) {
                    TextButton(
                        onClick = onExportCdtFailureLog,
                        enabled = state.operation == null,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Text("导出上次刷入失败日志") }
                }
                Box(Modifier.weight(1f)) {
                    if (tab == 0) {
                        VoltageScreen(
                            state = state,
                            onRefresh = onRefresh,
                            onCheckCompatibility = onCheckCompatibility,
                            onBackupCdt = onBackupCdt,
                            onEnableCdt = onEnableCdt,
                            onRestoreCdt = onRestoreCdt,
                            onRestoreCdtFromFile = onRestoreCdtFromFile,
                            onRebootNow = onRebootNow,
                        )
                    } else {
                        BenchmarkScreen(
                            state = state,
                            onPreset = onPreset,
                            onCore = onCore,
                            onThreads = onThreads,
                            onDuration = onDuration,
                            onMode = onMode,
                            onWorkload = onWorkload,
                            onFrequency = onFrequency,
                            onStart = onStart,
                            onStop = onStop,
                        )
                    }
                }
            }
            val operation = state.operation
            if (operation != null && operation != Operation.LOADING &&
                (tab != 0 || operation != Operation.READING)
            ) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 3.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExpressiveLoadingIndicator(Modifier.size(28.dp))
                        state.status?.let { status ->
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                    }
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

@Composable
private fun CrashLogNavIcon() {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.22f, h * 0.12f),
            size = Size(w * 0.56f, h * 0.76f),
            cornerRadius = CornerRadius(w * 0.08f),
            style = Stroke(width = w * 0.08f),
        )
        for (row in listOf(0.36f, 0.50f, 0.64f)) {
            drawLine(tint, Offset(w * 0.37f, h * row), Offset(w * 0.63f, h * row), w * 0.07f, StrokeCap.Round)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TunerPreview() {
    TunerTheme {
        TunerApp(
            state = ScreenState(),
            onRefresh = {}, onCheckCompatibility = {}, onExportDiagnostics = {}, onDismissDiagnosticsResult = {},
            onExportCdtFailureLog = {}, onDismissCdtLogExportResult = {},
            onBackupCdt = {}, onDismissCdtBackupResult = {},
            onEnableCdt = {}, onRestoreCdt = {}, onRestoreCdtFromFile = {}, onRestoreFallbackClean = {},
            onDismissRestoreNoBackup = {}, onRebootNow = {}, onDismissReboot = {},
            onPreset = {}, onCore = {}, onThreads = {}, onDuration = {},
            onMode = {}, onWorkload = {}, onFrequency = { _, _ -> }, onStart = {}, onStop = {},
        )
    }
}
