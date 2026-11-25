package io.washy.sm8750.tuner

import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class Level(
    val domain: Int,
    val index: Int,
    val khz: Int,
    val microwatts: Int,
    val coefficient: Long?,
) {
    val millivolts: Int?
        get() {
            val denominator = (coefficient ?: return null) * khz
            val mV = (sqrt(microwatts * 1e9 / denominator) / 4).roundToInt() * 4
            return mV.takeIf { denominator * it * it / 1_000_000_000 == microwatts.toLong() }
        }
}

internal enum class PlanState {
    STOCK_UNMODIFIED,
    CDT_WRITTEN,
    CDT_RECOVERY_REQUIRED,
    EXTERNAL_RUNTIME,
    CORRUPT_CDT,
}

internal enum class BaselineSource {
    CURRENT_STOCK,
    PERSISTED,
    NONE,
}

internal data class DevicePlan(
    val bootId: String,
    val state: PlanState,
    val cdtDisk: String,
    val cdtKey7: String,
    val hasCdtBackup: Boolean,
    val cdtPlatform: String,
    val bootCdt: String,
    val bootPlatformInfo: String,
    val apnsSlot: String,
    val baselineSource: BaselineSource,
    val online: Set<Int>,
    val baseline: List<Level>?,
    val current: List<Level>,
    val sm8850: Boolean = false,
    val estimatedScmiVoltage: Boolean = false,
    val cdtFlashSupported: Boolean = true,
    val cdtPendingReboot: Boolean = false,
)

internal data class BenchmarkDevice(
    val bootId: String,
    val online: Set<Int>,
    val frequencies: Map<Int, List<Int>>,
    val fixedSupported: Boolean,
    val voltagePlanSupported: Boolean = false,
)

internal fun parseBenchmarkDevice(output: String): BenchmarkDevice {
    val lines = output.lineSequence().map(String::trim).toList()
    fun value(tag: String) = lines.single { it.startsWith("$tag ") }.removePrefix("$tag ")
    val online = value("ONLINE").split(',').flatMap {
        it.substringBefore('-').toInt()..it.substringAfter('-', it).toInt()
    }.toSet()
    val bootId = value("BOOT")
    require(bootId.isNotBlank() && online.isNotEmpty() && online.all { it in 0..7 }) { "CPU 拓扑读回不完整" }
    val frequencies = online.map(::domainFor).toSet().associateWith { domain ->
        val policy = if (domain == 0) 0 else 6
        val points = value("POLICY $policy").split(Regex("\\s+")).map(String::toInt)
        require(points.isNotEmpty() && points.all { it > 0 }) {
            "policy$policy 的系统频点表无效"
        }
        points.distinct().sorted()
    }
    return BenchmarkDevice(bootId, online, frequencies, value("FIXED") == "1", value("VOLTAGE") == "1")
}

internal fun List<Level>.frequenciesByDomain(): Map<Int, List<Int>> = groupBy(Level::domain, Level::khz)

internal enum class FrequencyMode(val argument: String) {
    MAX("max"),
    FIXED("fixed"),
    SYSTEM("system"),
}

internal enum class WorkloadKind(val argument: String, val label: String) {
    MIXED("mixed", "INT + FP"),
    INT("int", "纯 INT"),
    FP("fp", "纯 FP · OpenBLAS"),
}

internal fun domainFor(core: Int): Int = if (core < 6) 0 else 1

internal data class LoadConfig(
    val cores: Set<Int>,
    val threads: Int,
    val seconds: Int,
    val mode: FrequencyMode,
    val targets: Map<Int, Int>,
    val workload: WorkloadKind = WorkloadKind.MIXED,
) {
    val valid: Boolean
        get() = cores.isNotEmpty() && threads in cores.size..32 && seconds in 5..3600 &&
            (mode != FrequencyMode.FIXED || targets.keys == cores.map(::domainFor).toSet())
    val mask: Int get() = cores.fold(0) { mask, core -> mask or (1 shl core) }
}

internal data class CoreLoad(
    val core: Int,
    val threads: Int,
    val busyNs: Long,
    val iterations: Long,
    val cycles: Long,
    val intOps: Long = 0L,
    val fpOps: Long = 0L,
) {
    val activeMhz: Double? get() = cycles.takeIf { it > 0 && busyNs > 0 }?.let { it * 1000.0 / busyNs }
}

internal data class PolicyLoad(
    val minLimit: Int,
    val maxLimit: Int,
    val lowestFloor: Int,
    val highestFloor: Int,
    val residency: Map<Int, Long>,
) {
    val weightedAvgMhz: Double
        get() {
            val total = residency.values.sum().toDouble()
            if (total <= 0.0) return 0.0
            return residency.entries.sumOf { (khz, ticks) -> (ticks / total) * (khz / 1000.0) }
        }
}

internal data class PvcSample(
    val domain: Int,
    val khz: Int,
    val medianMv: Int,
    val count: Int,
)

internal enum class PvcCaptureStatus(val label: String) {
    OK("PMIC PVC 实测"),
    EMPTY("PVC 无稳态样本"),
    UNSUPPORTED("内核不支持 PVC"),
    BUSY("PVC 模块忙"),
    SKIPPED("未触发 PVC"),
}

internal enum class VoltageSourceKind(val label: String) {
    PVC_MEASURED("PMIC PVC 实测"),
    MIXED("PVC + SCMI"),
    SCMI_MODEL("SCMI 计划"),
    UNAVAILABLE("未读取"),
}

internal data class LoadStats(
    val elapsedNs: Long,
    val intWallNs: Long,
    val fpWallNs: Long,
    val cores: List<CoreLoad>,
    val policies: Map<Int, PolicyLoad>,
    val pvcStatus: PvcCaptureStatus,
    val pvcSamples: List<PvcSample>,
)

internal data class CoreStressDetail(
    val core: Int,
    val busyPercent: Double,
    val intGiops: Double?,
    val fpGflops: Double?,
    val activeMhz: Double?,
    val clockStretched: Boolean,
)

internal data class ClusterStressSummary(
    val rmsVoltageMv: Double?,
    val nominalWeightedMhz: Double,
    val meanActiveMhz: Double?,
    val pvcShare: Double,
)

internal data class StressSummary(
    val cores: List<CoreStressDetail>,
    val clusters: Map<Int, ClusterStressSummary>,
    val totalIntGiops: Double?,
    val totalFpGflops: Double?,
    val overallSource: VoltageSourceKind,
    val anyClockStretch: Boolean,
)

internal data class LoadRun(
    val config: LoadConfig,
    val plan: List<Level>,
    val stats: LoadStats,
    val thermalStart: Int?,
    val thermalEnd: Int?,
) {
    fun summarizeStress(): StressSummary = computeStressSummary(plan, stats)
}

private fun voltageSourceForCoverage(cov: Double, hasPlan: Boolean): VoltageSourceKind = when {
    cov >= 0.80 -> VoltageSourceKind.PVC_MEASURED
    cov > 0.0 -> VoltageSourceKind.MIXED
    hasPlan -> VoltageSourceKind.SCMI_MODEL
    else -> VoltageSourceKind.UNAVAILABLE
}

private fun computeStressSummary(plan: List<Level>, stats: LoadStats): StressSummary {
    val levelsByDomain = plan.groupBy { it.domain }.mapValues { (_, list) -> list.associateBy { it.khz } }
    val pvcByDomain = stats.pvcSamples.groupBy { it.domain }.mapValues { (_, list) -> list.associateBy { it.khz } }

    val details = stats.cores.map { core ->
        val weightedNomMhz = stats.policies.getValue(domainFor(core.core)).weightedAvgMhz
        val busyRatio = (core.busyNs.toDouble() / stats.elapsedNs).coerceIn(0.0, 1.0)
        val activeMhz = core.activeMhz
        val stretchRatio = if (activeMhz != null && weightedNomMhz > 0.0) {
            (activeMhz / weightedNomMhz).coerceIn(0.1, 1.0)
        } else 1.0
        val stretched = activeMhz != null && stretchRatio < 0.94
        val intGiops = if (stats.intWallNs > 0L && core.intOps > 0L) core.intOps / stats.intWallNs.toDouble() else null
        val fpGflops = if (stats.fpWallNs > 0L && core.fpOps > 0L) core.fpOps / stats.fpWallNs.toDouble() else null
        CoreStressDetail(
            core = core.core,
            busyPercent = 100.0 * busyRatio,
            intGiops = intGiops,
            fpGflops = fpGflops,
            activeMhz = activeMhz,
            clockStretched = stretched,
        )
    }

    val clusters = details.groupBy { domainFor(it.core) }.mapValues { (domain, items) ->
        val policy = stats.policies.getValue(domain)
        val totalTicks = policy.residency.values.sum()
        var pvcTicks = 0L
        var coveredTicks = 0L
        var weightedV2 = 0.0
        for ((khz, ticks) in policy.residency) {
            if (ticks <= 0) continue
            val pvc = pvcByDomain[domain]?.get(khz)?.takeIf { it.count >= 2 }
            val mv = pvc?.medianMv ?: levelsByDomain[domain]?.get(khz)?.millivolts ?: continue
            if (pvc != null) pvcTicks += ticks
            coveredTicks += ticks
            weightedV2 += ticks.toDouble() * mv * mv
        }
        val activeVals = items.mapNotNull { it.activeMhz }
        ClusterStressSummary(
            rmsVoltageMv = if (totalTicks > 0 && coveredTicks == totalTicks) sqrt(weightedV2 / totalTicks) else null,
            nominalWeightedMhz = policy.weightedAvgMhz,
            meanActiveMhz = activeVals.takeIf { it.isNotEmpty() }?.average(),
            pvcShare = pvcTicks.toDouble() / totalTicks,
        )
    }

    val totalIntGiops = if (details.any { it.intGiops != null }) details.sumOf { it.intGiops ?: 0.0 } else null
    val totalFpGflops = if (details.any { it.fpGflops != null }) details.sumOf { it.fpGflops ?: 0.0 } else null
    val measuredClusters = clusters.values.filter { it.rmsVoltageMv != null }
    return StressSummary(
        cores = details,
        clusters = clusters,
        totalIntGiops = totalIntGiops,
        totalFpGflops = totalFpGflops,
        overallSource = if (measuredClusters.isEmpty()) VoltageSourceKind.UNAVAILABLE
            else voltageSourceForCoverage(measuredClusters.map { it.pvcShare }.average(), plan.isNotEmpty()),
        anyClockStretch = details.any { it.clockStretched },
    )
}

internal fun parsePlan(output: String): DevicePlan {
    val lines = output.lines()
    fun field(name: String) = lines.single { it.startsWith("$name ") }.substringAfter(' ')
    fun optionalField(name: String) = lines.singleOrNull { it.startsWith("$name ") }?.substringAfter(' ')
    val model = optionalField("PLAN_MODEL")
    val sm8850 = model == "SM8850"
    val sm8845 = model == "SM8845"
    fun rows(name: String): List<Level> {
        val start = lines.indexOf("BEGIN $name")
        require(start >= 0) { "SCMI 计划缺失" }
        val levels = lines.drop(start + 1).takeWhile { !it.startsWith("BEGIN ") && it != "END" }
            .filter { it.startsWith("0,") || it.startsWith("1,") }
            .map {
                val p = it.split(',').map(String::toInt)
                require(p.size == 7 && p[1] == p[6] && p[3] > 0 && p[5] > 0)
                Level(p[0], p[1], p[5], p[3], coefficient = null)
            }
            .sortedWith(compareBy(Level::domain, Level::index))
        val domains = levels.groupBy(Level::domain)
        require(domains.keys == setOf(0, 1) && domains.values.all { domain ->
            (sm8850 || domain.size == 16) &&
                domain.map(Level::index) == domain.indices.toList() &&
                domain.zipWithNext().all { (a, b) -> a.khz < b.khz }
        }) { "SCMI 计划不完整" }
        return levels
    }

    fun applyVoltageModel(levels: List<Level>, pdp1Coefficient: Long): List<Level> =
        levels.map { it.copy(coefficient = if (it.domain == 0) 100L else pdp1Coefficient) }

    val online = field("ONLINE").split(',').flatMap {
        val first = it.substringBefore('-').toInt()
        first..it.substringAfter('-', it).toInt()
    }.toSet()
    val reported = rows("current")
    val (pdp1Coefficient, current) = if (sm8845) {
        // The device capture matches 136; another stock PDP image uses 150.
        listOf(136L, 150L)
            .map { coefficient -> coefficient to applyVoltageModel(reported, coefficient) }
            .singleOrNull { (_, plan) -> plan.all { it.millivolts != null } }
            ?: (null to reported)
    } else {
        val coefficient = if (sm8850) 150L else 136L
        coefficient to applyVoltageModel(reported, coefficient)
    }
    if (sm8850) require(current.all { it.millivolts != null }) { "SM8850 SCMI 电压模型校验失败" }
    val state = PlanState.valueOf(field("STATE").uppercase())
    val declaredBaselineSource = BaselineSource.valueOf(field("BASELINE_SOURCE").uppercase())
    val baseline = when (declaredBaselineSource) {
        BaselineSource.CURRENT_STOCK -> current
        BaselineSource.PERSISTED -> pdp1Coefficient?.let { coefficient ->
            runCatching { applyVoltageModel(rows("baseline"), coefficient) }.getOrNull()
                ?.takeIf { baseline ->
                    baseline.map { it.khz } == current.map { it.khz } &&
                        (!(sm8845 || sm8850) || baseline.all { it.millivolts != null })
                }
        }
        BaselineSource.NONE -> null
    }
    require(lines.contains("END")) { "SCMI 读回未完成" }

    return DevicePlan(
        bootId = field("BOOT"),
        state = state,
        cdtDisk = field("CDT_DISK"),
        cdtKey7 = field("CDT_KEY7"),
        hasCdtBackup = field("CDT_HAS_BACKUP") == "1",
        cdtPlatform = field("CDT_PLATFORM"),
        bootCdt = field("BOOT_CDT"),
        bootPlatformInfo = field("BOOT_PLATFORM_INFO"),
        apnsSlot = field("APNS_SLOT"),
        baselineSource = if (baseline == null) BaselineSource.NONE else declaredBaselineSource,
        online = online,
        baseline = baseline,
        current = current,
        sm8850 = sm8850,
        estimatedScmiVoltage = sm8850 || sm8845,
        cdtFlashSupported = optionalField("CDT_FLASH_SUPPORTED")?.let { it == "1" } ?: !sm8850,
        cdtPendingReboot = optionalField("CDT_PENDING_REBOOT") == "1",
    )
}

internal fun parseLoadStats(
    output: String,
    config: LoadConfig,
    frequencies: Map<Int, List<Int>>,
    allowExtraFrequencies: Boolean = false,
): LoadStats? {
    val lines = output.lines()
    if ("STOPPED" in lines) return null
    require("DONE" in lines && "FINISH" in lines) { "烤机采样未完整结束" }
    return parseLoadStatsLines(lines, config, frequencies, allowExtraFrequencies)
}

private fun parseLoadStatsLines(
    lines: List<String>,
    config: LoadConfig,
    frequencies: Map<Int, List<Int>>,
    allowExtraFrequencies: Boolean,
): LoadStats {
    val timeParts = lines.single { it.startsWith("TIME ") }.split(' ').drop(1).map(String::toLong)
    val elapsed = timeParts[0]
    val intWallNs = timeParts.getOrElse(1) { 0L }
    val fpWallNs = timeParts.getOrElse(2) { 0L }
    require(elapsed > 0) { "烤机时长无效" }

    val cores = lines.filter { it.startsWith("CPU ") }.map { line ->
        val parts = line.split(' ')
        require(parts.size >= 13) { "核心算力输出字段不完整" }
        val workload = parts.subList(7, 13).map(String::toLong)
        CoreLoad(
            core = parts[1].toInt(),
            threads = parts[2].toInt(),
            busyNs = parts[3].toLong(),
            iterations = parts[4].toLong(),
            cycles = parts[5].toLong(),
            intOps = workload[1],
            fpOps = workload[4],
        )
    }
    require(
        cores.size == config.cores.size && cores.map { it.core }.toSet() == config.cores &&
            cores.sumOf { it.threads } == config.threads &&
            cores.all { it.threads > 0 && it.busyNs > 0 && it.iterations > 0 },
    ) { "实际执行的绑核配置与请求不一致" }

    val residency = lines.filter { it.startsWith("FREQ ") }.map { line ->
        val parts = line.split(' ')
        Triple(domainFor(parts[1].toInt()), parts[2].toInt(), parts[3].toLong())
    }.groupBy { it.first }
    val expectedDomains = config.cores.map(::domainFor).toSet()
    require(residency.keys == expectedDomains) { "频率驻留缺少集群" }

    val limits = lines.filter { it.startsWith("LIMIT ") }.map { line ->
        val p = line.split(' ').drop(1).map(String::toInt)
        require(
            p.size == 6 && p[2] in 1..p[3] && p[3] <= p[1] &&
                p[4] in 1..p[5] && p[5] <= p[1],
        ) { "频率约束数据无效" }
        p
    }.associateBy { domainFor(it[0]) }
    require(limits.keys == expectedDomains) { "频率上限缺少集群" }

    val profiles = residency.mapValues { (domain, entries) ->
        val dwell = entries.associate { it.second to it.third }
        val expected = frequencies.getValue(domain).toSet()
        val matching = if (allowExtraFrequencies) dwell.keys.containsAll(expected) else dwell.keys == expected
        require(
            entries.size == dwell.size && matching &&
                dwell.values.all { it >= 0 } && dwell.values.sum() > 0,
        ) { "频率驻留与设备频点表不匹配" }
        val limit = limits.getValue(domain)
        PolicyLoad(limit[2], limit[3], limit[4], limit[5], dwell)
    }

    val pvcStatus = lines.singleOrNull { it.startsWith("PVC_STATUS ") }?.substringAfter(' ')?.let { tag ->
        when (tag) {
            "ok" -> PvcCaptureStatus.OK
            "empty" -> PvcCaptureStatus.EMPTY
            "unsupported" -> PvcCaptureStatus.UNSUPPORTED
            "busy" -> PvcCaptureStatus.BUSY
            else -> PvcCaptureStatus.SKIPPED
        }
    } ?: PvcCaptureStatus.SKIPPED
    val pvcSamples = lines.filter { it.startsWith("PVC ") }.mapNotNull { line ->
        val parts = line.split(' ')
        if (parts.size != 7) return@mapNotNull null
        val values = parts.drop(1).map(String::toInt)
        PvcSample(domain = values[0], khz = values[1], medianMv = values[3], count = values[5])
    }
    return LoadStats(elapsed, intWallNs, fpWallNs, cores, profiles, pvcStatus, pvcSamples)
}
