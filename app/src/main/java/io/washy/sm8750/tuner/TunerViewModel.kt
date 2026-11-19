package io.washy.sm8750.tuner

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class Operation { READING, EXPORTING_DIAGNOSTICS, WRITING_CDT, BACKUP_CDT, REBOOTING, LOADING }

internal enum class RebootPrompt { ENABLED_CDT, RESTORED_CDT }

internal data class CorePreset(val label: String, val cores: Set<Int>)

internal val CORE_PRESETS = listOf(
    CorePreset("全核 0–7", (0..7).toSet()),
    CorePreset("双大核 6–7", setOf(6, 7)),
    CorePreset("中/小核 0–5", (0..5).toSet()),
    CorePreset("单核 CPU6", setOf(6)),
)

internal data class ScreenState(
    val plan: DevicePlan? = null,
    val cdtPartition: CdtPartition? = null,
    val cdtUncertain: Boolean = false,
    val operation: Operation? = null,
    val status: String? = null,
    val loadPhaseElapsedSec: Int = 0,
    val loadStopping: Boolean = false,
    val error: String? = null,
    val cdtFailureFeedback: Boolean = false,
    val hasCdtFailureLog: Boolean = false,
    val cdtLogExportComplete: Boolean = false,
    val compatibilityReport: CompatibilityReport? = null,
    val diagnosticsSaved: Boolean = false,
    val cdtBackupResult: CdtBackupResult? = null,
    val rebootPrompt: RebootPrompt? = null,
    val pendingRestoreNoBackup: Boolean = false,
    val cores: Set<Int> = (0..7).toSet(),
    val threads: String = "8",
    val seconds: String = "15",
    val frequencyMode: FrequencyMode = FrequencyMode.MAX,
    val workload: WorkloadKind = WorkloadKind.FP,
    val fixedFrequencies: Map<Int, Int> = emptyMap(),
    val lastRun: LoadRun? = null,
) {
    val config: LoadConfig?
        get() {
            val threadCount = threads.toIntOrNull() ?: return null
            val duration = seconds.toIntOrNull() ?: return null
            val domains = cores.map(::domainFor).toSet()
            val targets = if (frequencyMode == FrequencyMode.FIXED) domains.mapNotNull { domain ->
                val levels = plan?.current.orEmpty().filter { it.domain == domain }
                val selected = fixedFrequencies[domain]?.takeIf { khz -> levels.any { it.khz == khz } }
                    ?: levels.maxOfOrNull { it.khz }
                selected?.let { domain to it }
            }.toMap() else emptyMap()
            return LoadConfig(cores, threadCount, duration, frequencyMode, targets, workload).takeIf { it.valid }
        }
}

internal class TunerViewModel(app: Application) : AndroidViewModel(app) {
    private val backend = RootBackend(app)
    private val thermal = app.getSystemService(PowerManager::class.java)
    private val _state = MutableStateFlow(ScreenState(hasCdtFailureLog = backend.hasCdtFailureLog))
    val state = _state.asStateFlow()
    private var control: LoadControl? = null

    init {
        // The legacy refresh loads PJZ110-specific probe modules.
        if (Build.MODEL.equals("PJZ110", ignoreCase = true)) refresh()
    }

    fun refresh() = launchOperation(Operation.READING, "正在读取 CDT 分区与电压计划…") {
        val wasUncertain = _state.value.cdtUncertain
        val recoveryMessage = try {
            val recovery = backend.retryCdtRecovery(appendFailureLog = wasUncertain)
            val partition = backend.readCdtPartition()
            _state.update {
                it.copy(cdtPartition = partition, cdtUncertain = false, hasCdtFailureLog = backend.hasCdtFailureLog)
            }
            when (recovery) {
                "previous_verified" -> "已恢复并校验操作前的 CDT。"
                "target_verified" -> "已确认并校验上次操作的目标 CDT。"
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(cdtPartition = null) }
            throw e
        }
        try {
            updatePlan(backend.read())
            if (recoveryMessage != null) _state.update { it.copy(error = recoveryMessage) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (recoveryMessage == null) throw e
            throw IllegalStateException("$recoveryMessage 电压计划读取失败，可稍后刷新。", e)
        }
    }

    fun checkCompatibility() = launchOperation(Operation.EXPORTING_DIAGNOSTICS, "正在检测兼容性…") {
        val report = backend.collectDiagnostics()
        _state.update { it.copy(compatibilityReport = report, diagnosticsSaved = false) }
    }

    fun exportDiagnostics(destination: Uri) {
        val report = _state.value.compatibilityReport ?: return
        launchOperation(Operation.EXPORTING_DIAGNOSTICS, "正在保存检测日志…") {
            _state.update { it.copy(diagnosticsSaved = false) }
            backend.exportDiagnostics(destination, report)
            _state.update { it.copy(diagnosticsSaved = true) }
        }
    }

    fun dismissDiagnosticsResult() = _state.update {
        if (it.operation != null) it else it.copy(compatibilityReport = null, diagnosticsSaved = false, error = null)
    }

    fun exportCdtFailureLog(destination: Uri) =
        launchOperation(Operation.EXPORTING_DIAGNOSTICS, "正在导出 CDT 刷入日志…") {
            backend.exportCdtFailureLog(destination)
            _state.update { it.copy(cdtLogExportComplete = true) }
        }

    fun dismissCdtLogExportResult() = _state.update { it.copy(cdtLogExportComplete = false) }

    fun backupCdt(destination: Uri) = launchOperation(Operation.BACKUP_CDT, "正在备份当前 CDT 分区…") {
        val result = backend.backupCdt(destination)
        _state.update { it.copy(cdtBackupResult = result) }
    }

    fun dismissCdtBackupResult() = _state.update { it.copy(cdtBackupResult = null) }

    fun enableCdt() {
        val plan = _state.value.plan ?: return
        if (plan.state != PlanState.STOCK_UNMODIFIED) return
        if (_state.value.cdtUncertain) return
        launchOperation(Operation.WRITING_CDT, "正在备份原厂 CDT 并写入 Key 7 = 1…") {
            applyCdtResult(backend.enableCdt(plan.bootId), RebootPrompt.ENABLED_CDT)
        }
    }

    fun restoreCdt(fallbackClean: Boolean = false) {
        val state = _state.value
        if (state.cdtUncertain) return
        val plan = state.plan
        val partition = state.cdtPartition
        val bootId = partition?.bootId ?: plan?.bootId ?: return
        if (partition?.canRestore != true && plan?.cdtDisk != "slt_enabled" &&
            plan?.hasCdtBackup != true && plan?.state != PlanState.EXTERNAL_RUNTIME) return
        _state.update { it.copy(pendingRestoreNoBackup = false) }
        launchOperation(Operation.WRITING_CDT, "正在还原 CDT 分区并逐字节校验…") {
            try {
                applyCdtResult(backend.restoreCdt(bootId, fallbackClean), RebootPrompt.RESTORED_CDT)
            } catch (_: MissingCdtBackupException) {
                _state.update { it.copy(pendingRestoreNoBackup = true) }
            }
        }
    }

    private fun applyCdtResult(result: CdtOperationResult, success: RebootPrompt) {
        result.plan?.let(::updatePlan)
        _state.update {
            it.copy(
                plan = result.plan,
                cdtPartition = result.partition,
                cdtUncertain = result.outcome == CdtOutcome.UNKNOWN,
                error = result.message,
                cdtFailureFeedback = result.message != null && result.logSaved,
                hasCdtFailureLog = backend.hasCdtFailureLog,
                rebootPrompt = success.takeIf {
                    result.outcome == CdtOutcome.VERIFIED && result.partitionChanged &&
                        (result.partition != null || result.plan != null)
                },
            )
        }
    }

    fun dismissRestoreNoBackup() = _state.update { it.copy(pendingRestoreNoBackup = false) }

    fun dismissRebootPrompt() = _state.update { it.copy(rebootPrompt = null) }

    fun rebootNow() {
        val state = _state.value
        if (state.cdtUncertain) return
        val bootId = state.cdtPartition?.bootId ?: state.plan?.bootId ?: return
        _state.update { it.copy(rebootPrompt = null) }
        launchOperation(Operation.REBOOTING, "正在重启设备…") {
            backend.reboot(bootId)
        }
    }

    fun applyPreset(cores: Set<Int>) = _state.update { state ->
        if (state.operation != null) return@update state
        val available = cores.intersect(state.plan?.online.orEmpty())
        if (available.isEmpty()) return@update state
        state.copy(cores = available, threads = available.size.toString())
    }

    fun toggleCore(core: Int) = _state.update { state ->
        if (state.operation != null || core !in state.plan?.online.orEmpty()) return@update state
        val selected = if (core in state.cores) state.cores - core else state.cores + core
        val threads = state.threads.toIntOrNull()
        state.copy(
            cores = selected,
            threads = if (threads != null && threads < selected.size) selected.size.toString() else state.threads,
        )
    }

    fun setThreads(input: String) = setNumber(input, 2) { state, value -> state.copy(threads = value) }

    fun setSeconds(input: String) = setNumber(input, 4) { state, value -> state.copy(seconds = value) }

    fun setQuickSeconds(sec: Int) = _state.update {
        if (it.operation == null) it.copy(seconds = sec.toString()) else it
    }

    fun setFrequencyMode(mode: FrequencyMode) = _state.update {
        if (it.operation == null) it.copy(frequencyMode = mode) else it
    }

    fun setWorkload(kind: WorkloadKind) = _state.update {
        if (it.operation == null) it.copy(workload = kind) else it
    }

    fun setFixedFrequency(domain: Int, khz: Int) = _state.update {
        if (it.operation == null) it.copy(fixedFrequencies = it.fixedFrequencies + (domain to khz)) else it
    }

    private fun setNumber(input: String, digits: Int, change: (ScreenState, String) -> ScreenState) {
        if (input.length <= digits && input.all { it in '0'..'9' }) {
            _state.update { if (it.operation == null) change(it, input) else it }
        }
    }

    fun startLoad() {
        val snapshot = _state.value
        val device = snapshot.plan ?: return
        val config = snapshot.config ?: return
        if (!device.online.containsAll(config.cores)) return
        launchOperation(Operation.LOADING, "正在准备压测…") {
            val session = LoadControl()
            control = session
            var firstPolicy: String? = null
            var ticks = 0
            try {
                val fresh = backend.read()
                updatePlan(fresh)
                check(
                    fresh.bootId == device.bootId && fresh.state == device.state &&
                        fresh.current == device.current && fresh.online.containsAll(config.cores),
                ) { "电压计划或在线核心已变化，请刷新后重试" }
                val thermalStart = thermal?.currentThermalStatus
                val stats = backend.runLoad(fresh, config, session) { line ->
                    if (line.startsWith("PROGRESS ")) {
                        val policy = line.substringAfter(' ').substringBefore(' ')
                        // The worker emits one line per active policy each second.
                        if (firstPolicy == null) firstPolicy = policy
                        if (policy == firstPolicy) {
                            ticks = (ticks + 1).coerceAtMost(config.seconds)
                            _state.update { it.copy(loadPhaseElapsedSec = ticks) }
                        }
                    }
                } ?: return@launchOperation
                val thermalEnd = thermal?.currentThermalStatus
                val after = backend.read()
                updatePlan(after)
                check(
                    after.bootId == fresh.bootId && after.current == fresh.current &&
                        after.state == fresh.state && after.online.containsAll(config.cores),
                ) { "压测期间电压计划或在线核心发生变化，结果未保留" }
                val run = LoadRun(config, fresh.current, stats, thermalStart, thermalEnd)
                _state.update { it.copy(lastRun = run) }
            } finally {
                control = null
            }
        }
    }

    fun stopLoad() {
        val session = control ?: return
        if (_state.value.loadStopping) return
        _state.update { it.copy(loadStopping = true) }
        viewModelScope.launch(Dispatchers.IO) { session.stop() }
    }

    override fun onCleared() {
        control?.stop()
    }

    private fun updatePlan(plan: DevicePlan) = _state.update {
        val sameBoot = it.plan?.bootId == plan.bootId
        val cores = it.cores.intersect(plan.online)
        it.copy(
            plan = plan,
            cores = cores.ifEmpty { setOfNotNull(plan.online.firstOrNull()) },
            lastRun = it.lastRun.takeIf { run -> sameBoot && run?.plan == plan.current },
        )
    }

    private fun launchOperation(operation: Operation, status: String, block: suspend () -> Unit) {
        if (_state.value.operation != null) return
        _state.update {
            it.copy(
                operation = operation,
                status = status,
                loadPhaseElapsedSec = 0,
                error = null,
                cdtFailureFeedback = false,
                hasCdtFailureLog = backend.hasCdtFailureLog,
                rebootPrompt = if (operation == Operation.WRITING_CDT) null else it.rebootPrompt,
            )
        }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (operation == Operation.READING) _state.update { it.copy(plan = null) }
                _state.update {
                    it.copy(
                        error = e.message ?: "操作失败",
                        cdtFailureFeedback = operation == Operation.READING && backend.hasCdtFailureLog,
                        hasCdtFailureLog = backend.hasCdtFailureLog,
                    )
                }
            } finally {
                _state.update {
                    it.copy(operation = null, status = null, loadPhaseElapsedSec = 0, loadStopping = false)
                }
            }
        }
    }
}
