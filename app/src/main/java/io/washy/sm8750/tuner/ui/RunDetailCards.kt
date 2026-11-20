package io.washy.sm8750.tuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.washy.sm8750.tuner.LoadRun
import io.washy.sm8750.tuner.PvcSample
import io.washy.sm8750.tuner.StressSummary
import io.washy.sm8750.tuner.VoltageSourceKind

@Composable
internal fun RunKpiGrid(
    summary: StressSummary,
    run: LoadRun,
) {
    val voltageTone = when (summary.overallSource) {
        VoltageSourceKind.PVC_MEASURED -> Tone.POSITIVE
        VoltageSourceKind.MIXED -> Tone.INFO
        VoltageSourceKind.SCMI_MODEL, VoltageSourceKind.UNAVAILABLE -> Tone.NEUTRAL
    }
    val rmsSummary = summary.clusters.toSortedMap().mapNotNull { (domain, cluster) ->
        cluster.rmsVoltageMv?.let { "P$domain ${decimal(it, 0)}mV" }
    }.joinToString(" · ")
    val avgFreqText = summary.clusters.toSortedMap().entries.joinToString(" · ") { (d, c) ->
        "P$d ${c.meanActiveMhz?.let { decimal(it, 0) } ?: "—"}"
    }
    val hasPmu = summary.clusters.values.any { it.meanActiveMhz != null }
    val meanBusy = summary.cores.map { it.busyPercent }.average()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                label = "INT 整数算力",
                value = summary.totalIntGiops?.let { "${decimal(it, 2)} G" } ?: "—",
                subtitle = if (summary.totalIntGiops != null) "GIOPS" else "仅浮点模式",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "FP 浮点算力",
                value = summary.totalFpGflops?.let { "${decimal(it, 2)} G" } ?: "—",
                subtitle = if (summary.totalFpGflops != null) "GFLOPS" else "仅整数模式",
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                label = "工作电压",
                value = summary.overallSource.label,
                subtitle = rmsSummary.ifEmpty { run.stats.pvcStatus.label },
                deltaText = if (run.stats.pvcSamples.isNotEmpty()) "${run.stats.pvcSamples.size}档PVC" else null,
                deltaTone = voltageTone,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "PMU 主频",
                value = avgFreqText.ifEmpty { "—" } + " MHz",
                subtitle = "平均忙碌 ${decimal(meanBusy, 1)}%",
                deltaText = if (!hasPmu) null else if (summary.anyClockStretch) "半频异常" else "时钟正常",
                deltaTone = if (summary.anyClockStretch) Tone.DANGER else Tone.POSITIVE,
                modifier = Modifier.weight(1f),
            )
        }

        if (summary.anyClockStretch) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = TunerThemeTokens.extended.dangerContainer) {
                Text(
                    "异常：PMU 实测主频低于标称 94%（半频降速）",
                    Modifier.padding(10.dp),
                    color = TunerThemeTokens.extended.danger,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
internal fun RunTechnicalDetails(run: LoadRun) {
    val summary = run.summarizeStress()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("逐核实测", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                for ((domain, policy) in run.stats.policies.toSortedMap()) {
                    val c = summary.clusters[domain]
                    val target = run.config.targets[domain]
                    val restricted = target != null && policy.minLimit < target
                    val held = target != null && policy.lowestFloor == target && policy.highestFloor == target &&
                        policy.minLimit == target && policy.maxLimit == target
                    val targetText = if (target == null) "" else {
                        val outcome = if (restricted) "上限曾低于目标" else if (held) "锁定" else "范围未锁定"
                        " · 目标 ${mhzLabel(target)}（$outcome）"
                    }
                    val range = "下限 ${frequencyRangeLabel(policy.lowestFloor, policy.highestFloor)}" +
                        " · 上限 ${frequencyRangeLabel(policy.minLimit, policy.maxLimit)}"
                    Text(
                        "PDP$domain · 驻留均频 ${decimal(c?.nominalWeightedMhz ?: policy.weightedAvgMhz)} MHz$targetText",
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        range,
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth()) {
                    Text("核", Modifier.weight(0.55f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("主频", Modifier.weight(0.95f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                    Text("忙碌", Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                    Text("INT", Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                    Text("FP", Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                }
                summary.cores.forEach { c ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("C${c.core}", Modifier.weight(0.55f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold).tabular())
                        Text(
                            (c.activeMhz?.let { "${decimal(it, 0)}" } ?: "—") + if (c.clockStretched) "!" else "",
                            Modifier.weight(0.95f),
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = if (c.clockStretched) TunerThemeTokens.extended.danger else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                        )
                        Text("${decimal(c.busyPercent, 1)}%", Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall.tabular(), textAlign = TextAlign.End)
                        Text(c.intGiops?.let { "${decimal(it, 2)}G" } ?: "—", Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall.tabular(), textAlign = TextAlign.End)
                        Text(c.fpGflops?.let { "${decimal(it, 2)}G" } ?: "—", Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall.tabular(), textAlign = TextAlign.End)
                    }
                }
            }
        }

        if (run.stats.pvcSamples.isNotEmpty()) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("PMIC PVC 实测电压", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        StatusPill("${run.stats.pvcSamples.size}档", Tone.POSITIVE)
                    }
                    run.stats.pvcSamples.sortedWith(compareBy(PvcSample::domain, PvcSample::khz)).forEach { s ->
                        Text(
                            "PDP${s.domain} @ ${decimal(s.khz / 1000.0)} MHz → ${s.medianMv} mV (n=${s.count})",
                            style = MaterialTheme.typography.bodySmall.tabular(),
                        )
                    }
                }
            }
        }
    }
}


internal fun mhzLabel(khz: Int): String = "${decimal(khz / 1000.0, 1)} MHz"

internal fun frequencyRangeLabel(lowKhz: Int, highKhz: Int): String =
    if (lowKhz == highKhz) mhzLabel(lowKhz)
    else "${decimal(lowKhz / 1000.0, 1)}–${mhzLabel(highKhz)}"
