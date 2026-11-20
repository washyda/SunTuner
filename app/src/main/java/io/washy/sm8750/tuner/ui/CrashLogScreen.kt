package io.washy.sm8750.tuner.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.washy.sm8750.tuner.CrashEntry
import io.washy.sm8750.tuner.CrashLogState
import io.washy.sm8750.tuner.downloadName
import io.washy.sm8750.tuner.formattedTime

@Composable
internal fun CrashLogScreen(
    state: CrashLogState,
    onRefresh: () -> Unit,
    onDelete: (String?) -> Unit,
    onDownload: (Uri, CrashEntry) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.entries.firstOrNull { it.id == selectedId }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val entry = state.entries.firstOrNull { it.id == exportId }
        exportId = null
        if (uri != null && entry != null) onDownload(uri, entry)
    }
    BackHandler(enabled = selected != null) { selectedId = null }
    pendingDelete?.let { target ->
        val clearAll = target.isEmpty()
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (clearAll) "清空崩溃日志？" else "删除这条崩溃日志？") },
            text = {
                Text("将删除对应的设备崩溃日志和 SunTuner 记录，操作无法撤销。系统只读记录可能保留。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(target.takeUnless(String::isEmpty))
                    },
                    enabled = !state.busy,
                ) { Text(if (clearAll) "清空" else "删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (selected == null) {
                TextButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新") }
                TextButton(onClick = { pendingDelete = "" }, enabled = !state.busy) {
                    Text("清空日志")
                }
            } else {
                TextButton(onClick = { selectedId = null }) { Text("返回列表") }
                TextButton(
                    onClick = {
                        exportId = selected.id
                        download.launch(selected.downloadName())
                    },
                    enabled = !state.busy,
                ) { Text("下载日志") }
                TextButton(onClick = { pendingDelete = selected.id }, enabled = !state.busy) { Text("删除") }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        (state.error ?: state.notice)?.let {
            Text(
                it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = if (state.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (selected != null) {
            CrashLogDetail(selected)
        } else if (state.entries.isEmpty() && !state.busy && state.error == null) {
            Text(
                "暂无可分析的崩溃记录", Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    Surface(
                        onClick = { selectedId = entry.id },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CrashLogHeading(entry)
                            entry.frame?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashLogDetail(entry: CrashEntry) {
    val sections = remember(entry.evidence) {
        entry.evidence.map { record -> record.label to record.text.lineSequence().chunked(50)
            .map { lines -> lines.joinToString("\n") }.toList() }
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CrashLogHeading(entry)
                    entry.thread?.let { Text("线程 $it", style = MaterialTheme.typography.bodyMedium) }
                    entry.cpu?.let { Text("执行上下文 CPU$it", style = MaterialTheme.typography.bodyMedium) }
                    entry.frame?.let { Text("栈顶 $it", style = MaterialTheme.typography.bodyMedium) }
                    entry.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        if (entry.evidence.isEmpty()) {
            item { Text("这条记录只保存了摘要。", style = MaterialTheme.typography.bodySmall) }
        }
        for ((label, chunks) in sections) {
            item { Text(label, style = MaterialTheme.typography.titleSmall) }
            items(chunks) { chunk ->
                SelectionContainer {
                    Text(chunk, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun CrashLogHeading(entry: CrashEntry) {
    Text(entry.kind.label, style = MaterialTheme.typography.titleMedium)
    Text(
        "${entry.formattedTime()} · ${entry.source}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}
