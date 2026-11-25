package io.washy.sm8750.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.washy.sm8750.tuner.SUN_TUNER_REPOSITORY
import io.washy.sm8750.tuner.UpdateState

@Composable
internal fun SettingsScreen(
    themeMode: ThemeMode,
    accent: ThemeAccent,
    dark: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentChange: (ThemeAccent) -> Unit,
    versionName: String,
    updateState: UpdateState,
    onCheckUpdate: () -> Unit,
    onDismissUpdate: (UpdateState.Available) -> Unit,
    onDownloadUpdate: (UpdateState.Available) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (updateState is UpdateState.Available) {
        AvailableUpdateDialog(updateState, { onDismissUpdate(updateState) }, { onDownloadUpdate(updateState) })
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading("设置")
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("深浅色", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in ThemeMode.entries) {
                            FilterChip(
                                selected = option == themeMode,
                                onClick = { onThemeModeChange(option) },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Text("主色", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in ThemeAccent.entries) {
                            FilterChip(
                                selected = option == accent,
                                onClick = { onAccentChange(option) },
                                label = { Text(option.label) },
                                leadingIcon = {
                                    Box(Modifier.size(14.dp).background(option.swatch(dark), CircleShape))
                                },
                            )
                        }
                    }
                }
            }
            AboutCard(versionName, updateState, onCheckUpdate, onOpenUrl)
        }
    }
}

@Composable
private fun AboutCard(
    versionName: String,
    updateState: UpdateState,
    onCheckUpdate: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val checking = updateState == UpdateState.Checking
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
    ) {
        Column {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenUrl(SUN_TUNER_REPOSITORY) },
                color = colors.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("GitHub", style = typography.titleSmall)
                        Text(SUN_TUNER_REPOSITORY, style = typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    SymbolIcon(TunerSymbol.NEXT, Modifier.size(20.dp), color = colors.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
            FilledTonalButton(
                onClick = onCheckUpdate,
                enabled = updateState == UpdateState.Idle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (checking || updateState == UpdateState.StartingDownload) {
                    ExpressiveLoadingIndicator(Modifier.padding(end = 8.dp).size(18.dp))
                }
                Text(when (updateState) {
                    UpdateState.Checking -> "检查中…"
                    UpdateState.StartingDownload -> "正在开始下载…"
                    else -> "检查更新"
                })
            }
            Text(
                "版本 $versionName",
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
                style = typography.labelSmall.tabular(),
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AvailableUpdateDialog(update: UpdateState.Available, onDismiss: () -> Unit, onDownload: () -> Unit) {
    val notes = remember(update.notes) {
        update.notes.lineSequence()
            .map { it.removePrefix("## ").replace(Regex("^- "), "• ") }
            .joinToString("\n").trim()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${update.version}") },
        text = if (notes.isBlank()) null else {
            { Text(notes, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("下载更新") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    TunerTheme {
        SettingsScreen(
            themeMode = ThemeMode.SYSTEM,
            accent = ThemeAccent.INDIGO,
            dark = false,
            onThemeModeChange = {},
            onAccentChange = {},
            versionName = "0.5.2beta",
            updateState = UpdateState.Available("0.5.3beta", "## 更新内容\n- 优化电压计划显示", ""),
            onCheckUpdate = {},
            onDismissUpdate = {},
            onDownloadUpdate = {},
            onOpenUrl = {},
        )
    }
}
