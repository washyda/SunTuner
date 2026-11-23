package io.washy.sm8750.tuner

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class Operation {
    READING, EXPORTING_DIAGNOSTICS, WRITING_CDT, BACKUP_CDT, REBOOTING, LOADING, GAME_TEST;

    val isLoad: Boolean get() = this == LOADING || this == GAME_TEST
}

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
    val benchmarkDevice: BenchmarkDevice? = null,
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
    val gameSession: GameSession? = null,
    val gameResult: GameResult? = null,
) {
    val benchmarkOnline: Set<Int> get() = benchmarkDevice?.online ?: plan?.online.orEmpty()
    val benchmarkFrequencies: Map<Int, List<Int>>
        get() = benchmarkDevice?.frequencies ?: plan?.current.orEmpty().frequenciesByDomain()

    val config: LoadConfig?
        get() {
            if (frequencyMode == FrequencyMode.FIXED && benchmarkDevice?.fixedSupported == false) return null
            val threadCount = threads.toIntOrNull() ?: return null
            val duration = seconds.toIntOrNull()?.takeIf { it >= 5 } ?: return null
            val domains = cores.map(::domainFor).toSet()
            val targets = if (frequencyMode == FrequencyMode.FIXED) domains.mapNotNull { domain ->
                val frequencies = benchmarkFrequencies[domain].orEmpty()
                val selected = fixedFrequencies[domain]?.takeIf { it in frequencies }
                    ?: frequencies.maxOrNull()
                selected?.let { domain to it }
            }.toMap() else emptyMap()
            return LoadConfig(cores, threadCount, duration, frequencyMode, targets, workload).takeIf { it.valid }
        }
}

internal data class CrashLogState(
    val entries: List<CrashEntry> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

internal class TunerViewModel(app: Application) : AndroidViewModel(app) {
    private val backend = RootBackend(app)
    private val isSm8850 = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.equals("SM8850", ignoreCase = true)
    private val gameStore = GameResultStore(app)
    private val crashLogStore = CrashLogStore(app)
    private val _crashLogState = MutableStateFlow(CrashLogState())
    val crashLogState = _crashLogState.asStateFlow()
    private val thermal = app.getSystemService(PowerManager::class.java)
    private val _state = MutableStateFlow(ScreenState(hasCdtFailureLog = backend.hasCdtFailureLog))
    val state = _state.asStateFlow()
    private var control: LoadControl? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = gameStore.read()
            _state.update { if (it.gameSession == null && it.gameResult == null) it.copy(gameResult = saved) else it }
        }
        // The legacy refresh loads PJZ110-specific probe modules.
        if (isSm8850 || Build.MODEL.equals("PJZ110", ignoreCase = true)) refresh()
    }

    fun refreshCrashLogs() = updateCrashLogs("读取崩溃日志失败") {
        val history = withContext(Dispatchers.IO) { crashLogStore.read() }
        _crashLogState.update { it.copy(entries = history.entries) }
        val scan = scanCrashLogs()
        withContext(Dispatchers.IO) {
            val updated = history.withScan(scan)
            if (updated != history) crashLogStore.write(updated)
            _crashLogState.update { it.copy(entries = updated.entries) }
        }
    }

    fun deleteCrashLogs(entryId: String?) = updateCrashLogs("删除崩溃日志失败") {
        val history = withContext(Dispatchers.IO) { crashLogStore.read() }
        val scan = scanCrashLogs()
        val current = history.withScan(scan)
        val selected = current.entries.filter { entryId == null || it.id == entryId }
        if (entryId != null) check(selected.isNotEmpty()) { "记录已变化，请刷新后重试" }

        val clearAll = entryId == null
        val archive = listOf("delete_critical")
        fun commandFor(record: CrashEvidence): List<String>? = when {
            record.deleteArgs.isEmpty() -> null
            clearAll && record.deleteArgs.first() == archive.first() -> archive
            else -> record.deleteArgs
        }
        val evidence = selected.flatMap { it.evidence } + if (clearAll) scan.kernelEvidence else emptyList()
        val commands = (listOfNotNull(archive.takeIf { clearAll }) + evidence.mapNotNull(::commandFor)).distinct()
        val failures = mutableMapOf<List<String>, String>()
        for (command in commands) {
            try {
                backend.deleteCrashEvidence(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures[command] = e.message?.take(180) ?: "设备拒绝删除"
            }
        }

        val failedEntries = selected.mapNotNull { entry ->
            val retainedEvidence = entry.evidence.filter { commandFor(it) in failures }
            when {
                retainedEvidence.isNotEmpty() -> entry.copy(evidence = retainedEvidence)
                archive in failures && entry.evidence.isEmpty() && entry.source.contains("OPlus critical.log") -> entry
                else -> null
            }
        }
        val remaining = current.copy(entries = (current.entries - selected.toSet()) + failedEntries)
        withContext(Dispatchers.IO) { crashLogStore.write(remaining) }
        _crashLogState.update { it.copy(entries = remaining.entries) }

        val after = scanCrashLogs()
        val updated = remaining.withScan(after)
        withContext(Dispatchers.IO) { crashLogStore.write(updated) }
        val observed = if (clearAll) after.entries else after.entries.filter { it.id == entryId }
        val stillPresent = observed.flatMap { it.evidence }.mapNotNull(::commandFor)
            .any { it in commands && it !in failures }
        val failureDetails = failures.values.distinct().joinToString("\n")
        val readOnly = evidence.any { it.deleteArgs.isEmpty() }
        _crashLogState.update {
            when {
                failures.isNotEmpty() -> it.copy(error = "部分设备日志删除失败。\n$failureDetails")
                stillPresent -> it.copy(error = "设备日志仍存在，可能已被系统重新写入。")
                readOnly -> it.copy(notice = "可删除的记录已处理。系统重启原因或只读内核日志仍由系统提供。")
                else -> it.copy(notice = "日志已删除")
            }
        }
        _crashLogState.update { it.copy(entries = updated.entries) }
    }

    fun exportCrashLog(destination: Uri, entry: CrashEntry) {
        updateCrashLogs("保存崩溃日志失败") {
            backend.exportCrashLog(destination, entry)
            _crashLogState.update { it.copy(notice = "崩溃日志已下载") }
        }
    }

    private suspend fun scanCrashLogs(): CrashScan {
        val raw = backend.readCrashLogs()
        val bootStart = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return withContext(Dispatchers.IO) { parseCrashLogs(raw, bootStart) }
    }

    private fun updateCrashLogs(errorLabel: String, action: suspend () -> Unit) {
        if (_crashLogState.value.busy) return
        _crashLogState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _crashLogState.update {
                    it.copy(error = listOfNotNull(errorLabel, e.message?.take(180)).joinToString("。"))
                }
            } finally {
                _crashLogState.update { it.copy(busy = false) }
            }
        }
    }

    fun refresh() = launchOperation(Operation.READING, "正在读取 CDT 分区与电压计划…") {
        val wasUncertain = _state.value.cdtUncertain
        val recovery = try {
            val recovered = backend.retryCdtRecovery(appendFailureLog = wasUncertain)
            val partition = backend.readCdtPartition()
            _state.update {
                it.copy(cdtPartition = partition, cdtUncertain = false, hasCdtFailureLog = backend.hasCdtFailureLog)
            }
            Result.success(when (recovered) {
                "previous_verified" -> "已恢复并校验操作前的 CDT。"
                "target_verified" -> "已确认并校验上次操作的目标 CDT。"
                else -> null
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(cdtPartition = null, hasCdtFailureLog = backend.hasCdtFailureLog) }
            Result.failure(e)
        }
        val cpu = if (isSm8850) {
            val device = try {
                backend.readBenchmarkDevice()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(benchmarkDevice = null) }
                recovery.getOrThrow()
                throw e
            }
            _state.update {
                it.copy(
                    benchmarkDevice = device,
                    cores = it.cores.intersect(device.online).ifEmpty { setOfNotNull(device.online.firstOrNull()) },
                    frequencyMode = if (!device.fixedSupported && it.frequencyMode == FrequencyMode.FIXED) {
                        FrequencyMode.SYSTEM
                    } else it.frequencyMode,
                    lastRun = it.lastRun.takeIf { _ -> it.benchmarkDevice == device },
                )
            }
            device
        } else null
        val recoveryMessage = recovery.getOrThrow()
        if (cpu?.voltagePlanSupported == false) {
            _state.update {
                it.copy(plan = null, error = recoveryMessage)
            }
            return@launchOperation
        }
        try {
            val plan = backend.read()
            if (plan.sm8850) {
                require(plan.bootId == cpu?.bootId) { "启动 ID 已改变，请刷新电压计划" }
            }
            updatePlan(plan)
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
        if (plan.state != PlanState.STOCK_UNMODIFIED || !plan.cdtFlashSupported) return
        if (_state.value.cdtUncertain) return
        launchOperation(Operation.WRITING_CDT, "正在备份原厂 CDT 并写入 Key 7 = 1…") {
            applyCdtResult(backend.enableCdt(plan.bootId), RebootPrompt.ENABLED_CDT)
        }
    }

    fun restoreCdt(fallbackClean: Boolean = false, source: Uri? = null) {
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
                applyCdtResult(backend.restoreCdt(bootId, fallbackClean, source), RebootPrompt.RESTORED_CDT)
            } catch (e: MissingCdtBackupException) {
                if (isSm8850) throw IllegalStateException("未找到这台设备的完整原厂 CDT 备份，无法还原", e)
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
        val available = cores.intersect(state.benchmarkOnline)
        if (available.isEmpty()) return@update state
        state.copy(cores = available, threads = available.size.toString())
    }

    fun toggleCore(core: Int) = _state.update { state ->
        if (state.operation != null || core !in state.benchmarkOnline) return@update state
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
        val config = snapshot.config ?: return
        if (!snapshot.benchmarkOnline.containsAll(config.cores)) return
        val plan = snapshot.plan
        val cpu = snapshot.benchmarkDevice ?: plan?.let {
            BenchmarkDevice(it.bootId, it.online, it.current.frequenciesByDomain(), fixedSupported = true)
        } ?: return

        suspend fun verifyInputs() {
            if (snapshot.benchmarkDevice != null) {
                check(backend.readBenchmarkDevice() == cpu) { "设备频点或在线核心已变化，请刷新后重试" }
            }
            if (plan != null) {
                val current = backend.read()
                updatePlan(current)
                check(current.bootId == cpu.bootId && current.bootId == plan.bootId &&
                    current.state == plan.state && current.current == plan.current &&
                    current.online.containsAll(config.cores)) { "电压计划或在线核心已变化，请刷新后重试" }
            }
        }

        launchOperation(Operation.LOADING, "正在准备压测…") {
            val session = LoadControl()
            control = session
            try {
                verifyInputs()
                val thermalStart = thermal?.currentThermalStatus
                val stats = runTimedLoad(cpu, config, session, allowExtraFrequencies = snapshot.benchmarkDevice != null)
                    ?: return@launchOperation
                val thermalEnd = thermal?.currentThermalStatus
                verifyInputs()
                val run = LoadRun(config, plan?.current.orEmpty(), stats, thermalStart, thermalEnd)
                _state.update { it.copy(lastRun = run) }
            } finally {
                control = null
            }
        }
    }

    fun startGameTest() = launchOperation(Operation.GAME_TEST, "准备稳定性测试…") {
        val session = GameSession()
        _state.update { it.copy(gameSession = session, gameResult = GameResult(GameOutcome.RUNNING)) }
        try {
            val result = withContext(Dispatchers.IO) { runGameTest(session) }
            _state.update { it.copy(gameResult = result) }
            withContext(Dispatchers.IO) { gameStore.write(result) }
        } finally {
            session.stopped = true
            _state.update {
                val result = it.gameResult?.let { current ->
                    if (current.outcome == GameOutcome.RUNNING) current.copy(outcome = GameOutcome.INTERRUPTED)
                    else current
                }
                it.copy(gameSession = null, gameResult = result)
            }
        }
    }

    private suspend fun runGameTest(session: GameSession): GameResult {
        val start = System.nanoTime()
        gameStore.write(GameResult(GameOutcome.RUNNING))
        var lastProgress = 0
        val cpu = GameCpuWorkers(session)
        try {
            backend.startGameTrace(cpu.awaitTids()).use { trace ->
                trace.awaitReady()
                while (!session.stopped && session.failure.get() == null && session.lastFrameNs == 0L &&
                    System.nanoTime() - start < 12_000_000_000L
                ) delay(20)
                if (!session.stopped && session.lastFrameNs == 0L) {
                    session.failure.compareAndSet(null, "GPU 场景启动超时")
                }
                if (session.lastFrameNs != 0L && session.failure.get() == null && !session.stopped) {
                    session.beginNs = System.nanoTime()
                    while (!session.stopped && session.failure.get() == null) {
                        val now = System.nanoTime()
                        val elapsedMs = session.elapsedMs(now)
                        if (now - session.lastFrameNs > 3_000_000_000L) {
                            session.failure.compareAndSet(null, "GPU 场景连续 3 秒无新帧")
                            break
                        }
                        val elapsed = (elapsedMs / 1000).toInt()
                        if (elapsed == GAME_SECONDS) break
                        gameBurst(elapsedMs, session.workerCount)?.let(cpu::pulse)
                        if (elapsed > lastProgress) {
                            lastProgress = elapsed
                            val progress = GameResult(GameOutcome.RUNNING, elapsed, gameRounds(elapsedMs))
                            _state.update { it.copy(gameResult = progress) }
                            if (elapsed % 4 == 0) gameStore.write(progress)
                        }
                        delay(12)
                    }
                }
                val end = System.nanoTime()
                val userStopped = session.stopped
                val elapsedMs = session.elapsedMs(end)
                session.stopped = true
                trace.close()
                val coverage = if (session.beginNs != 0L) trace.coverage(session.beginNs, end, session.gpuSpans()) else null
                val error = session.failure.get()
                val outcome = when {
                    error != null -> GameOutcome.FAILED
                    userStopped -> GameOutcome.STOPPED
                    else -> GameOutcome.COMPLETED
                }
                return GameResult(outcome, (elapsedMs / 1000).toInt(), gameRounds(elapsedMs), coverage, error)
            }
        } finally {
            session.stopped = true
            cpu.close()
        }
    }

    fun stopGameTest() {
        _state.value.gameSession?.stopped = true
    }

    private suspend fun runTimedLoad(
        device: BenchmarkDevice,
        config: LoadConfig,
        session: LoadControl,
        allowExtraFrequencies: Boolean,
    ): LoadStats? {
        var firstPolicy: String? = null
        var ticks = 0
        return backend.runLoad(device, config, session, allowExtraFrequencies) { line ->
            if (line.startsWith("PROGRESS ")) {
                val policy = line.substringAfter(' ').substringBefore(' ')
                // The worker emits one line per active policy each second.
                if (firstPolicy == null) firstPolicy = policy
                if (policy == firstPolicy) {
                    ticks = (ticks + 1).coerceAtMost(config.seconds)
                    _state.update { it.copy(loadPhaseElapsedSec = ticks) }
                }
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
