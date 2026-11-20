package io.washy.sm8750.tuner.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.washy.sm8750.tuner.GAME_SECONDS
import io.washy.sm8750.tuner.GameCoverage
import io.washy.sm8750.tuner.GameRenderer
import io.washy.sm8750.tuner.GameResult
import io.washy.sm8750.tuner.GameSession
import kotlinx.coroutines.isActive

@Composable
internal fun GameScenarioCard(
    result: GameResult?,
    enabled: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("3D 场景 · CPU + GPU 联动测试", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("稳定性测试")
            }
            if (result != null) {
                Text(
                    "${if (result.legacyScenario) "旧场景 · " else ""}${result.outcome.label} · ${result.elapsed} / $GAME_SECONDS 秒",
                    style = MaterialTheme.typography.titleSmall,
                )
                result.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                result.coverage?.let { report ->
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "收起实测数据" else "查看实测数据")
                    }
                    if (showDetails) GameCoverageDetails(report, result.rounds)
                }
            }
        }
    }
}

@Composable
private fun GameCoverageDetails(report: GameCoverage, rounds: Int) {
    val style = MaterialTheme.typography.bodySmall.tabular()
    if (report.incompleteTrace) Text("调频与调度追踪不完整", style = style)
    if (report.frequencies == null) {
        Text("升降频 · 无法观测", style = style)
    } else {
        for (row in report.frequencies) {
            val span = if (row.lowKhz != null && row.highKhz != null) {
                " · ${row.lowKhz / 1000}–${row.highKhz / 1000} MHz"
            } else ""
            Text("${row.policy} · 升 ${row.up} / 降 ${row.down} · 快速往返 ${row.fastPairs}$span", style = style)
        }
    }
    val moves = report.cpuMoves?.toString() ?: "无法观测"
    val clusters = report.clusterMoves?.toString() ?: "无法观测"
    Text("工作线程跨核 $moves · 跨频率簇 $clusters", style = style)
    val overlap = report.overlapRounds?.let { "$it / ${report.gpuTimedRounds} 个可测轮次" } ?: "无法观测"
    Text("CPU / GPU 重叠 · $overlap", style = style)
    Text("GPU 有效计时 ${report.gpuTimedRounds} / $rounds 轮", style = style)
}

@Composable
internal fun GameScenarioScreen(
    session: GameSession,
    elapsed: Int,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onStop)
    val context = LocalContext.current
    val surface = remember(session) {
        GLSurfaceView(context).apply {
            val hz = (display?.refreshRate ?: context.display.refreshRate).coerceIn(30f, 240f)
            setEGLContextClientVersion(3)
            setRenderer(GameRenderer(session, (1_000_000_000L / hz).toLong()))
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }
    LaunchedEffect(session) {
        while (isActive) {
            withFrameNanos {
                if (!session.stopped) surface.requestRender()
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
        Surface(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("稳定性测试", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onStop) { Text("停止测试") }
                }
                LinearProgressIndicator(progress = { elapsed / GAME_SECONDS.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(
                    "$elapsed / $GAME_SECONDS 秒",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GameScenarioCardPreview() {
    TunerTheme { GameScenarioCard(result = null, enabled = true, onStart = {}) }
}
