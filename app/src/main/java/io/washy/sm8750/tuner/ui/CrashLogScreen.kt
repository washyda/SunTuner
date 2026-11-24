package io.washy.sm8750.tuner.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.washy.sm8750.tuner.CrashEntry
import io.washy.sm8750.tuner.CrashKind
import io.washy.sm8750.tuner.CrashLogState
import io.washy.sm8750.tuner.downloadName
import io.washy.sm8750.tuner.formattedTime

@OptIn(ExperimentalMaterial3Api::class)
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
    var menuOpen by remember { mutableStateOf(false) }
    val selected = state.entries.firstOrNull { it.id == selectedId }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val entry = state.entries.firstOrNull { it.id == exportId }
        exportId = null
        if (uri != null && entry != null) onDownload(uri, entry)
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeading("崩溃日志") {
            TonalAction(TunerSymbol.REFRESH, "刷新日志", !state.busy, loading = state.busy, onClick = onRefresh)
            Box {
                TonalAction(TunerSymbol.MORE, "更多日志操作", !state.busy) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("清空日志") },
                        onClick = {
                            menuOpen = false
                            pendingDelete = ""
                        },
                    )
                }
            }
        }
        (state.error ?: state.notice)?.let { message ->
            Text(
                message,
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = if (state.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.entries.isEmpty() && !state.busy && state.error == null) {
            Surface(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LogKindIcon(CrashKind.UNKNOWN)
                    Text("暂无可分析的崩溃记录", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(state.entries, key = { _, entry -> entry.id }) { index, entry ->
                    Surface(
                        onClick = { selectedId = entry.id },
                        modifier = Modifier.fillMaxWidth(),
                        shape = listSegmentShape(index, state.entries.size),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LogKindIcon(entry.kind)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(entry.kind.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    entry.source,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                entry.formattedTime().drop(5).take(11),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            SymbolIcon(TunerSymbol.NEXT)
                        }
                    }
                }
            }
        }
    }

    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedId = null },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            CrashLogDetail(
                selected,
                Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.56f).dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        exportId = selected.id
                        download.launch(selected.downloadName())
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy,
                ) {
                    SymbolIcon(TunerSymbol.DOWNLOAD)
                    Text("下载日志", Modifier.padding(start = 8.dp))
                }
                TextButton(onClick = { pendingDelete = selected.id }, enabled = !state.busy) {
                    Text("删除", color = if (state.busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
        }
    }

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
                        selectedId = null
                        onDelete(target.takeUnless(String::isEmpty))
                    },
                    enabled = !state.busy,
                ) { Text(if (clearAll) "清空" else "删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

}

@Composable
private fun LogKindIcon(kind: CrashKind) {
    val (symbol, tone) = when (kind) {
        CrashKind.GPU -> TunerSymbol.DISPLAY to Tone.DANGER
        CrashKind.CPU -> TunerSymbol.CHIP to Tone.WARNING
        CrashKind.DISPLAY -> TunerSymbol.DISPLAY to Tone.INFO
        CrashKind.WATCHDOG -> TunerSymbol.REFRESH to Tone.NEUTRAL
        CrashKind.UNKNOWN -> TunerSymbol.ALERT to Tone.NEUTRAL
    }
    val (background, foreground) = toneColors(tone)
    Surface(shape = CircleShape, color = background) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            SymbolIcon(symbol, color = foreground)
        }
    }
}

@Composable
private fun CrashLogDetail(entry: CrashEntry, modifier: Modifier = Modifier) {
    val sections = remember(entry.evidence) {
        entry.evidence.map { record -> record.label to record.text.lineSequence().chunked(50)
            .map { lines -> lines.joinToString("\n") }.toList() }
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
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
