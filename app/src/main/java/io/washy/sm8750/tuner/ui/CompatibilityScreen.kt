package io.washy.sm8750.tuner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.washy.sm8750.tuner.CompatibilityReport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompatibilityScreen(
    report: CompatibilityReport,
    saving: Boolean,
    missingFiles: Int?,
    error: String?,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("兼容性检测结果") },
                navigationIcon = { TextButton(onClick = onBack, enabled = !saving) { Text("返回") } },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else if (missingFiles != null) {
                        Text(
                            if (missingFiles == 0) "兼容性包已保存"
                            else "兼容性包已保存，有 ${missingFiles} 项未采集。详情见 diagnostics.txt。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (saving) ExpressiveLoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
                            Text(if (saving) "正在保存…" else "保存兼容性包")
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(report.model.ifBlank { "未知机型" }, style = MaterialTheme.typography.titleLarge)
                        if (report.kernel.isNotBlank()) {
                            Text(report.kernel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompatibilityRow(
                            "Root 采集",
                            if (report.rootCollected) "已采集" to Tone.POSITIVE else "未采集" to Tone.WARNING,
                        )
                        if (report.rootCollected) {
                            HorizontalDivider()
                            CompatibilityRow("CDT 分区", when {
                                report.recoveryPending -> "有待恢复记录" to Tone.WARNING
                                report.cdtDisk == "legacy_v6" -> "旧版写入 · 需还原" to Tone.WARNING
                                report.cdtDisk == "slt_enabled" -> "CDT 已写入" to Tone.INFO
                                report.cdtDisk in setOf("empty", "stock") -> "原厂状态" to Tone.NEUTRAL
                                report.cdtDisk == "corrupt" -> "分区数据异常" to Tone.DANGER
                                report.cdtRead == "partition_not_found" -> "未找到分区" to Tone.WARNING
                                else -> "无法确认" to Tone.WARNING
                            })
                            if (report.cdtLayout != null) {
                                HorizontalDivider()
                                CompatibilityRow("CDT 布局", when (report.cdtLayout) {
                                    "v3_6" -> "v3 · 6 字节" to Tone.INFO
                                    "v4_v5_7" -> "v4/v5 · 7 字节" to Tone.INFO
                                    "v6_9" -> "v6 · 9 字节（无键）" to Tone.INFO
                                    "v6_10" -> "v6 · 10 字节" to Tone.INFO
                                    "v6_legacy_7" -> "旧版 v6 · 7 字节" to Tone.WARNING
                                    else -> "未识别" to Tone.WARNING
                                })
                                CompatibilityRow("布局来源", when (report.cdtLayoutSource) {
                                    "active_xbl_config" -> "活动槽 xbl_config" to Tone.INFO
                                    "cdt_and_active_xbl_config" -> "CDT 与 xbl_config" to Tone.INFO
                                    "cdt" -> "当前 CDT" to Tone.INFO
                                    "platform" -> "平台信息" to Tone.INFO
                                    "legacy_app" -> "旧版 SunTuner" to Tone.WARNING
                                    else -> "无" to Tone.WARNING
                                })
                                CompatibilityRow("布局校验", when (report.cdtLayoutCheck) {
                                    "restore_required" -> "需先还原" to Tone.WARNING
                                    "ambiguous" -> "模板不唯一" to Tone.WARNING
                                    "unavailable" -> "固件不可读" to Tone.WARNING
                                    "no_match" -> "无匹配模板" to Tone.WARNING
                                    "no_space" -> "没有足够空间" to Tone.WARNING
                                    "confirmed" -> when {
                                        report.cdtLayoutCanEnable -> "符合写入布局" to Tone.POSITIVE
                                        report.cdtDisk == "slt_enabled" -> "已解析" to Tone.INFO
                                        else -> "没有足够空间" to Tone.WARNING
                                    }
                                    else -> "布局未确认" to Tone.WARNING
                                })
                            }
                            HorizontalDivider()
                            CompatibilityRow("CDT 只读标记", when {
                                "1" in report.readOnlyFlags -> "检测到只读" to Tone.WARNING
                                "0" in report.readOnlyFlags -> "未发现只读标记" to Tone.NEUTRAL
                                else -> "无法读取" to Tone.WARNING
                            })
                            HorizontalDivider()
                            CompatibilityRow("SCMI 接口", when (report.scmiBus) {
                                "present" -> "已发现" to Tone.INFO
                                "unavailable" -> "未发现" to Tone.WARNING
                                else -> "未知" to Tone.NEUTRAL
                            })
                            HorizontalDivider()
                            CompatibilityRow("SELinux", when (report.selinuxMode) {
                                "Enforcing" -> "强制模式" to Tone.INFO
                                "Permissive" -> "宽容模式" to Tone.WARNING
                                "Disabled" -> "已关闭" to Tone.WARNING
                                else -> "未知" to Tone.NEUTRAL
                            })
                        } else {
                            Text("已保留机型和内核信息。检查 Root 授权后可重新检测。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (report.rootCollected) {
                            Text(
                                "保存时会采集活动槽的 uefi、xbl、xbl_config 和可读的内核配置。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompatibilityRow(label: String, status: Pair<String, Tone>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        StatusPill(status.first, status.second)
    }
}

@Preview(showBackground = true)
@Composable
private fun CompatibilityScreenPreview() {
    TunerTheme {
        CompatibilityScreen(
            report = CompatibilityReport(
                "MODEL=PJZ110\nKERNEL=6.6.89-android15\n",
                "CDT_DISK=stock\nCDT_BLOCKDEV_RO=0\nSCMI_BUS=present\nSELINUX_MODE=Enforcing\n",
            ),
            saving = false,
            missingFiles = null,
            error = null,
            onSave = {},
            onBack = {},
        )
    }
}
