package com.eis.silentoptimizer.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.eis.silentoptimizer.AppContainer
import com.eis.silentoptimizer.core.ChangeRecord
import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.core.RiskLevel
import com.eis.silentoptimizer.daily.DailyOptimizer
import com.eis.silentoptimizer.debloat.ClassifiedPackage
import com.eis.silentoptimizer.debloat.PlannedAction
import com.eis.silentoptimizer.debloat.Recommendation
import com.eis.silentoptimizer.device.BatterySnapshot
import com.eis.silentoptimizer.device.DeviceProfile
import com.eis.silentoptimizer.device.DeviceProbe
import com.eis.silentoptimizer.device.ThermalSnapshot
import com.eis.silentoptimizer.gaming.DetectedGame
import com.eis.silentoptimizer.gaming.GameDetector
import com.eis.silentoptimizer.gaming.GameProfile
import com.eis.silentoptimizer.gaming.GamingState
import com.eis.silentoptimizer.inventory.InstalledPackage
import com.eis.silentoptimizer.inventory.PackageInventory
import com.eis.silentoptimizer.privilege.PrivilegeState
import com.eis.silentoptimizer.recommend.Priority
import com.eis.silentoptimizer.recommend.RecInput
import com.eis.silentoptimizer.recommend.RecItem
import com.eis.silentoptimizer.recommend.RecommendationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aggregated dashboard/UI state. */
data class EisUiState(
    val loading: Boolean = true,
    val device: DeviceProfile? = null,
    val battery: BatterySnapshot? = null,
    val thermal: ThermalSnapshot? = null,
    val privileges: PrivilegeState? = null,
    val installedCount: Int = 0,
    val systemCount: Int = 0,
    val disabledCount: Int = 0,
    val recentActions: List<ChangeRecord> = emptyList(),
    val recommendations: List<RecItem> = emptyList(),
    val priorities: Set<Priority> = setOf(
        Priority.EVERYDAY_RESPONSIVENESS,
        Priority.BATTERY_LIFE,
    ),
    val statusMessage: String? = null,
)

data class DebloatUiState(
    val scanning: Boolean = false,
    val items: List<ClassifiedPackage> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val categoryFilter: Recommendation? = null,
    val lastResult: Pair<String, List<OpResult>>? = null, // label -> results
    val busy: Boolean = false,
)

data class GamingUiState(
    val games: List<DetectedGame> = emptyList(),
    val profile: GameProfile = GameProfile.BALANCED,
    val targetRefreshRate: Int? = null,
    val restrictBackground: Boolean = false,
    val thermalWarnCelsius: Double = 42.0,
    val lastResults: List<Pair<String, OpResult>> = emptyList(),
    val busy: Boolean = false,
)

data class RootLabUiState(
    val loading: Boolean = false,
    val available: Boolean = false,
    val cpu: List<com.eis.silentoptimizer.rootlab.SysfsControl> = emptyList(),
    val gpu: List<com.eis.silentoptimizer.rootlab.SysfsControl> = emptyList(),
    val thermal: List<com.eis.silentoptimizer.rootlab.ThermalReading> = emptyList(),
    val zram: Map<String, String> = emptyMap(),
    val io: Map<String, String> = emptyMap(),
    val kernel: Map<String, String> = emptyMap(),
    val modules: List<com.eis.silentoptimizer.privilege.RootModule> = emptyList(),
    val manager: String = "-",
    val message: String? = null,
)

class EisViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(EisUiState())
    val ui: StateFlow<EisUiState> = _ui.asStateFlow()

    private val _debloat = MutableStateFlow(DebloatUiState())
    val debloat: StateFlow<DebloatUiState> = _debloat.asStateFlow()

    private val _gaming = MutableStateFlow(GamingUiState())
    val gaming: StateFlow<GamingUiState> = _gaming.asStateFlow()

    private var fullInventory: List<InstalledPackage> = emptyList()

    init {
        refresh()
    }

    // --- Global refresh ----------------------------------------------------

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            val ctx = appContext()
            val priv = container.refreshPrivileges()
            val profile = withContext(Dispatchers.IO) { DeviceProbe.profile(ctx) }
            val battery = withContext(Dispatchers.IO) { DeviceProbe.battery(ctx) }
            val thermal = withContext(Dispatchers.IO) { DeviceProbe.thermal(ctx) }
            val inventory = withContext(Dispatchers.IO) { PackageInventory.scan(ctx) }
            fullInventory = inventory

            _ui.value = _ui.value.copy(
                loading = false,
                device = profile,
                battery = battery,
                thermal = thermal,
                privileges = priv,
                installedCount = inventory.size,
                systemCount = inventory.count { it.isSystem },
                disabledCount = inventory.count { !it.isEnabled },
                recentActions = container.journal.entries().take(8),
                recommendations = evaluateRecommendations(profile, thermal, inventory),
                statusMessage = null,
            )
        }
    }

    fun setPriorities(priorities: Set<Priority>) {
        _ui.value = _ui.value.copy(priorities = priorities)
        val d = _ui.value.device ?: return
        val t = _ui.value.thermal
        _ui.value = _ui.value.copy(
            recommendations = evaluateRecommendations(d, t, fullInventory),
        )
    }

    private fun evaluateRecommendations(
        profile: DeviceProfile,
        thermal: ThermalSnapshot?,
        inventory: List<InstalledPackage>,
    ): List<RecItem> = RecommendationEngine.evaluate(
        RecInput(
            profile = profile,
            mode = container.privilegeState.mode,
            priorities = _ui.value.priorities,
            installedPackages = inventory.map { it.packageName }.toSet(),
            availableStorageBytes = profile.availableStorageBytes,
            totalStorageBytes = profile.totalStorageBytes,
            thermalStatus = thermal?.thermalStatus ?: -1,
            animationScaleKnown = false,
            animationScale = null,
        ),
    )

    private fun appContext(): Context =
        EisContextHolder.context ?: error("ViewModel used before MainActivity attached context")

    // --- Privilege actions ---------------------------------------------------

    fun requestShizuku() {
        container.requestShizukuPermission()
        refresh()
    }

    fun reprobePrivileges() {
        viewModelScope.launch {
            container.refreshPrivileges()
            _ui.value = _ui.value.copy(privileges = container.privilegeState)
        }
    }

    // --- Debloat --------------------------------------------------------------

    fun scanDebloat() {
        viewModelScope.launch {
            _debloat.value = _debloat.value.copy(scanning = true)
            val ctx = appContext()
            val inventory = withContext(Dispatchers.IO) { PackageInventory.scan(ctx) }
            fullInventory = inventory
            val classified = withContext(Dispatchers.Default) {
                container.debloatEngine.classify(inventory)
            }
            _debloat.value = _debloat.value.copy(
                scanning = false,
                items = classified,
                selected = classified.filter {
                    it.category == Recommendation.RECOMMENDED && it.risk == RiskLevel.SAFE
                }.map { it.pkg.packageName }.toSet(),
                lastResult = null,
            )
        }
    }

    fun setDebloatQuery(q: String) {
        _debloat.value = _debloat.value.copy(query = q)
    }

    fun setDebloatFilter(cat: Recommendation?) {
        _debloat.value = _debloat.value.copy(categoryFilter = cat)
    }

    fun toggleDebloatSelection(packageName: String) {
        val cur = _debloat.value.selected
        _debloat.value = _debloat.value.copy(
            selected = if (packageName in cur) cur - packageName else cur + packageName,
        )
    }

    fun debloatAction(action: String, dryRun: Boolean, allowBatch: Boolean = false) {
        val state = _debloat.value
        val chosen = state.items.filter { it.pkg.packageName in state.selected }
        if (chosen.isEmpty()) {
            _debloat.value = state.copy(lastResult = "No packages selected" to emptyList())
            return
        }
        viewModelScope.launch {
            _debloat.value = _debloat.value.copy(busy = true, lastResult = null)
            val plan = container.debloatEngine.plan(chosen, action)
            val results = container.debloatEngine.execute(
                plan = plan,
                dryRun = dryRun,
                batchToken = if (allowBatch) "confirmed-by-user" else if (plan.size <= 1) "single" else null,
            )
            val ok = results.count { it.success }
            val label = (if (dryRun) "Dry run: " else "Executed: ") +
                "$ok/${plan.size} succeeded" +
                (results.firstOrNull { !it.success }?.let { " - first failure: ${it.message}" } ?: "")
            // Refresh inventory to reflect new states.
            val ctx = appContext()
            val inventory = withContext(Dispatchers.IO) { PackageInventory.scan(ctx) }
            fullInventory = inventory
            val classified = withContext(Dispatchers.Default) {
                container.debloatEngine.classify(inventory)
            }
            _debloat.value = _debloat.value.copy(
                busy = false,
                items = classified,
                lastResult = label to results,
            )
            _ui.value = _ui.value.copy(
                recentActions = container.journal.entries().take(8),
                disabledCount = inventory.count { !it.isEnabled },
            )
        }
    }

    fun restorePlan(): List<PlannedAction> =
        container.debloatEngine.restorePlan(fullInventory)

    fun debloatReport(): String = container.debloatEngine.report(_debloat.value.items)

    // --- Gaming -------------------------------------------------------------

    fun detectGames() {
        viewModelScope.launch {
            val ctx = appContext()
            val games = withContext(Dispatchers.IO) { GameDetector.detectGames(ctx) }
            _gaming.value = _gaming.value.copy(games = games)
        }
    }

    fun setGameProfile(profile: GameProfile) {
        _gaming.value = _gaming.value.copy(profile = profile)
        // Preset refresh targets per profile; user can override.
        _gaming.value = _gaming.value.copy(
            targetRefreshRate = when (profile) {
                GameProfile.COMPETITIVE -> 120
                GameProfile.MAX_STABILITY, GameProfile.BATTERY_SAVER -> 60
                GameProfile.BALANCED, GameProfile.CUSTOM -> null
            },
        )
    }

    fun setGamingRefreshRate(hz: Int?) {
        _gaming.value = _gaming.value.copy(targetRefreshRate = hz, profile = GameProfile.CUSTOM)
    }

    fun applyGamingProfile(dryRun: Boolean) {
        val g = _gaming.value
        viewModelScope.launch {
            _gaming.value = _gaming.value.copy(busy = true, lastResults = emptyList())
            val state = GamingState(
                profile = g.profile,
                targetRefreshRate = g.targetRefreshRate,
                restrictBackground = g.restrictBackground,
                thermalWarnCelsius = g.thermalWarnCelsius,
            )
            val results = container.gamingEngine.apply(state, dryRun)
            _gaming.value = _gaming.value.copy(busy = false, lastResults = results)
            _ui.value = _ui.value.copy(recentActions = container.journal.entries().take(8))
        }
    }

    // --- Daily --------------------------------------------------------------

    fun applyAnimationScale(which: String, scale: Float, dryRun: Boolean, onDone: (OpResult) -> Unit) {
        viewModelScope.launch {
            val res = container.dailyOptimizer.setAnimationScale(which, scale, dryRun)
            _ui.value = _ui.value.copy(recentActions = container.journal.entries().take(8))
            onDone(res)
        }
    }

    fun readAnimationScale(which: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(container.dailyOptimizer.readAnimationScale(which))
        }
    }

    fun applyRefreshRate(hz: Int?, dryRun: Boolean, onDone: (OpResult) -> Unit) {
        viewModelScope.launch {
            val res = if (hz == null) container.dailyOptimizer.clearPeakRefreshRate(dryRun)
            else container.dailyOptimizer.setPeakRefreshRate(hz, dryRun)
            onDone(res)
        }
    }

    // --- Safety --------------------------------------------------------------

    fun journalEntries(): List<ChangeRecord> = container.journal.entries()

    fun undoEntry(entry: ChangeRecord, dryRun: Boolean, onDone: (OpResult) -> Unit) {
        viewModelScope.launch {
            val res = container.safetyCenter.undo(entry, dryRun)
            _ui.value = _ui.value.copy(recentActions = container.journal.entries().take(8))
            onDone(res)
        }
    }

    fun exportBackup(): String = container.safetyCenter.exportBackup()

    fun failureReport(): String = container.safetyCenter.failureReport()

    fun recoveryGuidance(): List<String> = container.safetyCenter.recoveryGuidance()

    fun componentReport(): Map<String, String> = container.componentReport()

    // --- Root Lab ------------------------------------------------------------

    private val _rootLab = MutableStateFlow(RootLabUiState())
    val rootLab: StateFlow<RootLabUiState> = _rootLab.asStateFlow()

    fun loadRootLab() {
        viewModelScope.launch {
            if (container.privilegeState.mode != PrivilegeMode.ROOT) {
                _rootLab.value = RootLabUiState(
                    available = false,
                    message = "Root Lab requires root. Current mode: ${container.privilegeState.mode}. " +
                        container.privilegeState.notes.joinToString(" "),
                )
                return@launch
            }
            _rootLab.value = _rootLab.value.copy(loading = true, message = null)
            val rl = container.rootLab
            val cpu = rl.cpuGovernors()
            val gpu = rl.gpuControls()
            val thermal = rl.thermalZones()
            val zram = rl.zramState()
            val io = rl.ioScheduler()
            val kernel = rl.kernelInfo()
            val modules = try { com.eis.silentoptimizer.privilege.RootGate.listModules() } catch (_: Throwable) { emptyList() }
            val manager = try { com.eis.silentoptimizer.privilege.RootGate.describeManager() } catch (_: Throwable) { "unknown" }
            _rootLab.value = RootLabUiState(
                loading = false,
                available = true,
                cpu = cpu, gpu = gpu, thermal = thermal,
                zram = zram, io = io, kernel = kernel,
                modules = modules, manager = manager,
                message = if (cpu.isEmpty() && gpu.isEmpty())
                    "No readable cpufreq/devfreq nodes found on this device (vendor kernels vary)."
                else null,
            )
        }
    }

    fun writeSysfs(path: String, value: String, previous: String?, dryRun: Boolean, onDone: (OpResult) -> Unit) {
        viewModelScope.launch {
            val res = container.rootLab.writeControl(path, value, previous, dryRun)
            _ui.value = _ui.value.copy(recentActions = container.journal.entries().take(8))
            onDone(res)
        }
    }

    fun hasRoot(): Boolean = container.privilegeState.mode == PrivilegeMode.ROOT

    fun runnerMode(): PrivilegeMode = container.privilegeState.mode
}

class EisViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EisViewModel::class.java)) {
            return EisViewModel(container) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
