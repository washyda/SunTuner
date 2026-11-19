package io.washy.sm8750.tuner

import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class Level(val domain: Int, val index: Int, val khz: Int, val microwatts: Int) {
    val millivolts: Int?
        get() {
            val coefficient = if (domain == 0) 100L else 136L
            val denominator = coefficient * khz
            val mV = (sqrt(microwatts * 1e9 / denominator) / 4).roundToInt() * 4
            return mV.takeIf { denominator * it * it / 1_000_000_000 == microwatts.toLong() }
        }
}

internal enum class PlanState {
    STOCK_UNMODIFIED,
    CDT_PENDING_REBOOT,
    CDT_ACTIVE,
    RESTORE_PENDING_REBOOT,
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
)

internal enum class FrequencyMode(val argument: String, val label: String) {
    MAX("max", "性能模式"),
    FIXED("fixed", "锁频"),
    SYSTEM("system", "系统调频"),
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
    val intBusyNs: Long = 0L,
    val intOps: Long = 0L,
    val intCycles: Long = 0L,
    val fpBusyNs: Long = 0L,
    val fpOps: Long = 0L,
    val fpCycles: Long = 0L,
) {
    val activeMhz: Double? get() = cycles.takeIf { it > 0 && busyNs > 0 }?.let { it * 1000.0 / busyNs }
    val intActiveMhz: Double? get() = intCycles.takeIf { it > 0 && intBusyNs > 0 }?.let { it * 1000.0 / intBusyNs }
    val fpActiveMhz: Double? get() = fpCycles.takeIf { it > 0 && fpBusyNs > 0 }?.let { it * 1000.0 / fpBusyNs }
}

internal data class PolicyLoad(
    val nominalMax: Int,
    val minLimit: Int,
    val maxLimit: Int,
    val lowestFloor: Int,
    val highestFloor: Int,
    val residency: Map<Int, Long>,
) {
    val peak: Int get() = residency.keys.filter { residency.getValue(it) > 0 }.max()
    fun share(khz: Int): Double = (residency[khz] ?: 0).toDouble() / residency.values.sum().coerceAtLeast(1L)
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
    val minMv: Int,
    val medianMv: Int,
    val maxMv: Int,
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
    val threads: Int,
    val busyPercent: Double,
    val mopsPerSec: Double,
    val intGiops: Double?,
    val fpGflops: Double?,
    val nominalWeightedMhz: Double,
    val activeMhz: Double?,
    val intActiveMhz: Double?,
    val fpActiveMhz: Double?,
    val clockStretched: Boolean,
    val rmsVoltageMv: Double,
    val voltageSource: VoltageSourceKind,
)

internal data class ClusterStressSummary(
    val domain: Int,
    val rmsVoltageMv: Double,
    val nominalWeightedMhz: Double,
    val meanActiveMhz: Double?,
    val minLimitMhz: Double,
    val maxLimitMhz: Double,
    val voltageSource: VoltageSourceKind,
    val pvcCoverage: Double,
)

internal data class StressSummary(
    val cores: List<CoreStressDetail>,
    val clusters: Map<Int, ClusterStressSummary>,
    val totalMopsPerSec: Double,
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

private fun voltageSourceForCoverage(cov: Double): VoltageSourceKind = when {
    cov >= 0.80 -> VoltageSourceKind.PVC_MEASURED
    cov > 0.0 -> VoltageSourceKind.MIXED
    else -> VoltageSourceKind.SCMI_MODEL
}

private fun computeStressSummary(plan: List<Level>, stats: LoadStats): StressSummary {
    val levelsByDomain = plan.groupBy { it.domain }.mapValues { (_, list) -> list.associateBy { it.khz } }
    val pvcByDomain = stats.pvcSamples.groupBy { it.domain }.mapValues { (_, list) -> list.associateBy { it.khz } }

    data class CoreEval(val detail: CoreStressDetail, val pvcShare: Double)

    val evals = stats.cores.map { core ->
        val domain = domainFor(core.core)
        val policy = stats.policies.getValue(domain)
        val totalTicks = policy.residency.values.sum().toDouble().coerceAtLeast(1.0)
        val levelMap = levelsByDomain[domain].orEmpty()
        val pvcMap = pvcByDomain[domain].orEmpty()

        var weightedNomMhz = 0.0
        var weightedV2 = 0.0
        var pvcShare = 0.0

        for ((khz, ticks) in policy.residency) {
            if (ticks <= 0) continue
            val share = ticks / totalTicks
            val scmiMv = (levelMap[khz]?.millivolts ?: 0).toDouble()
            val pvcMv = pvcMap[khz]?.takeIf { it.count >= 2 }?.medianMv?.toDouble()
            val mv = pvcMv ?: scmiMv
            if (pvcMv != null) pvcShare += share
            weightedNomMhz += share * (khz / 1000.0)
            weightedV2 += share * mv * mv
        }

        val busyRatio = (core.busyNs.toDouble() / stats.elapsedNs).coerceIn(0.0, 1.0)
        val activeMhz = core.activeMhz
        val stretchRatio = if (activeMhz != null && weightedNomMhz > 0.0) {
            (activeMhz / weightedNomMhz).coerceIn(0.1, 1.0)
        } else 1.0
        val stretched = activeMhz != null && stretchRatio < 0.94
        val mopsPerSec = core.iterations * 1000.0 / stats.elapsedNs
        val intGiops = if (stats.intWallNs > 0L && core.intOps > 0L) core.intOps / stats.intWallNs.toDouble() else null
        val fpGflops = if (stats.fpWallNs > 0L && core.fpOps > 0L) core.fpOps / stats.fpWallNs.toDouble() else null
        CoreEval(
            detail = CoreStressDetail(
                core = core.core,
                threads = core.threads,
                busyPercent = 100.0 * busyRatio,
                mopsPerSec = mopsPerSec,
                intGiops = intGiops,
                fpGflops = fpGflops,
                nominalWeightedMhz = weightedNomMhz,
                activeMhz = activeMhz,
                intActiveMhz = core.intActiveMhz,
                fpActiveMhz = core.fpActiveMhz,
                clockStretched = stretched,
                rmsVoltageMv = sqrt(weightedV2),
                voltageSource = voltageSourceForCoverage(pvcShare),
            ),
            pvcShare = pvcShare,
        )
    }

    val details = evals.map { it.detail }
    val clusters = evals.groupBy { domainFor(it.detail.core) }.mapValues { (domain, list) ->
        val items = list.map { it.detail }
        val cov = list.map { it.pvcShare }.average()
        val policy = stats.policies.getValue(domain)
        val activeVals = items.mapNotNull { it.activeMhz }
        ClusterStressSummary(
            domain = domain,
            rmsVoltageMv = items.first().rmsVoltageMv,
            nominalWeightedMhz = items.first().nominalWeightedMhz,
            meanActiveMhz = activeVals.takeIf { it.isNotEmpty() }?.average(),
            minLimitMhz = policy.minLimit / 1000.0,
            maxLimitMhz = policy.maxLimit / 1000.0,
            voltageSource = voltageSourceForCoverage(cov),
            pvcCoverage = cov,
        )
    }

    val totalMops = details.sumOf { it.mopsPerSec }
    val totalIntGiops = if (details.any { it.intGiops != null }) details.sumOf { it.intGiops ?: 0.0 } else null
    val totalFpGflops = if (details.any { it.fpGflops != null }) details.sumOf { it.fpGflops ?: 0.0 } else null
    val meanCov = evals.map { it.pvcShare }.average()
    return StressSummary(
        cores = details,
        clusters = clusters,
        totalMopsPerSec = totalMops,
        totalIntGiops = totalIntGiops,
        totalFpGflops = totalFpGflops,
        overallSource = voltageSourceForCoverage(meanCov),
        anyClockStretch = details.any { it.clockStretched },
    )
}

internal fun parsePlan(output: String): DevicePlan {
    val lines = output.lines()
    fun field(name: String) = lines.single { it.startsWith("$name ") }.substringAfter(' ')
    fun rows(name: String): List<Level> {
        val start = lines.indexOf("BEGIN $name")
        require(start >= 0) { "SCMI 计划缺失" }
        val levels = lines.drop(start + 1).takeWhile { !it.startsWith("BEGIN ") && it != "END" }
            .filter { it.startsWith("0,") || it.startsWith("1,") }
            .map {
                val p = it.split(',').map(String::toInt)
                require(p.size == 7 && p[1] == p[6] && p[3] > 0 && p[5] > 0)
                Level(p[0], p[1], p[5], p[3])
            }
            .sortedWith(compareBy(Level::domain, Level::index))
        require(levels.size == 32 && levels.groupBy { it.domain }.values.all { domain ->
            domain.map { it.index } == (0..15).toList() &&
                domain.zipWithNext().all { (a, b) -> a.khz < b.khz }
        }) { "SCMI 计划不完整" }
        return levels
    }

    val online = field("ONLINE").split(',').flatMap {
        val first = it.substringBefore('-').toInt()
        first..it.substringAfter('-', it).toInt()
    }.toSet()
    val current = rows("current")
    val state = PlanState.valueOf(field("STATE").uppercase())
    val baselineSource = BaselineSource.valueOf(field("BASELINE_SOURCE").uppercase())
    val baseline = when (baselineSource) {
        BaselineSource.CURRENT_STOCK -> current
        BaselineSource.PERSISTED -> rows("baseline")
        BaselineSource.NONE -> null
    }
    require(baseline == null || baseline.map { it.khz } == current.map { it.khz }) { "频点表已变更" }
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
        baselineSource = baselineSource,
        online = online,
        baseline = baseline,
        current = current,
    )
}

internal fun parseLoadStats(output: String, config: LoadConfig, plan: List<Level>): LoadStats? {
    val lines = output.lines()
    if ("STOPPED" in lines) return null
    require("DONE" in lines && "FINISH" in lines) { "烤机采样未完整结束" }
    return parseLoadStatsLines(lines, config, plan)
}

private fun parseLoadStatsLines(lines: List<String>, config: LoadConfig, plan: List<Level>): LoadStats {
    val timeParts = lines.single { it.startsWith("TIME ") }.split(' ').drop(1).map(String::toLong)
    val elapsed = timeParts[0]
    val intWallNs = timeParts.getOrElse(1) { 0L }
    val fpWallNs = timeParts.getOrElse(2) { 0L }
    require(elapsed > 0) { "烤机时长无效" }

    val cores = lines.filter { it.startsWith("CPU ") }.map { line ->
        val parts = line.split(' ')
        require(parts.size >= 13) { "核心算力输出字段不完整" }
        CoreLoad(
            core = parts[1].toInt(),
            threads = parts[2].toInt(),
            busyNs = parts[3].toLong(),
            iterations = parts[4].toLong(),
            cycles = parts[5].toLong(),
            intBusyNs = parts[7].toLong(),
            intOps = parts[8].toLong(),
            intCycles = parts[9].toLong(),
            fpBusyNs = parts[10].toLong(),
            fpOps = parts[11].toLong(),
            fpCycles = parts[12].toLong(),
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
        require(
            entries.size == 16 &&
                dwell.keys == plan.filter { it.domain == domain }.map { it.khz }.toSet() &&
                dwell.values.all { it >= 0 } && dwell.values.sum() > 0,
        ) { "频率驻留不能与 SCMI 计划匹配" }
        val limit = limits.getValue(domain)
        PolicyLoad(limit[1], limit[2], limit[3], limit[4], limit[5], dwell)
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
        val p = line.split(' ')
        if (p.size == 7) PvcSample(p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6].toInt()) else null
    }
    return LoadStats(elapsed, intWallNs, fpWallNs, cores, profiles, pvcStatus, pvcSamples)
}
