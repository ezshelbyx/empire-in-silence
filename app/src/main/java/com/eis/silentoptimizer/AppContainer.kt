package com.eis.silentoptimizer

import android.content.Context
import com.eis.silentoptimizer.core.JournalStore
import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.daily.DailyOptimizer
import com.eis.silentoptimizer.debloat.DebloatEngine
import com.eis.silentoptimizer.gaming.GamingEngine
import com.eis.silentoptimizer.privilege.PrivilegeDetector
import com.eis.silentoptimizer.privilege.PrivilegeState
import com.eis.silentoptimizer.privilege.RootGate
import com.eis.silentoptimizer.privilege.ShizukuGate
import com.eis.silentoptimizer.recommend.RecommendationEngine
import com.eis.silentoptimizer.rootlab.RootLab
import com.eis.silentoptimizer.safety.JsonJournalStore
import com.eis.silentoptimizer.safety.SafetyCenter
import com.eis.silentoptimizer.shell.CommandRunner
import com.eis.silentoptimizer.shell.RootCommandRunner
import com.eis.silentoptimizer.shell.ShizukuCommandRunner
import com.eis.silentoptimizer.shell.StandardCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manual dependency container (no Hilt - keeps the build lean for a
 * system utility and avoids codegen on low-end targets).
 *
 * Owns: privilege state machine, journal, runners, and all engines.
 */
class AppContainer(private val appContext: Context) {

    val journal: JournalStore = JsonJournalStore(File(appContext.filesDir, "eis_journal.json"))

    val shizukuGate = ShizukuGate(appContext)

    @Volatile var privilegeState: PrivilegeState = PrivilegeState(
        mode = PrivilegeMode.STANDARD,
        shizukuInstalled = false,
        shizukuRunning = false,
        shizukuAuthorized = false,
        rootAvailable = false,
        rootGranted = false,
        rootManager = com.eis.silentoptimizer.privilege.RootManager.NONE,
        notes = listOf("Not yet probed."),
    )
        private set

    @Volatile private var rootRunner: RootCommandRunner? = null
    private val shizukuRunner = ShizukuCommandRunner(shizukuGate)
    private val standardRunner = StandardCommandRunner()

    /** Highest usable runner for the current state (ROOT > SHIZUKU > STANDARD). */
    fun runner(): CommandRunner? = when (privilegeState.mode) {
        PrivilegeMode.ROOT -> rootRunner ?: RootCommandRunner().also { rootRunner = it }
        PrivilegeMode.SHIZUKU -> shizukuRunner
        PrivilegeMode.STANDARD -> null // no shell in standard mode; UI guides instead
    }

    val safetyCenter = SafetyCenter(journal) { runner() }

    val debloatEngine = DebloatEngine({ runner() }, journal, shizukuGate)

    val dailyOptimizer = DailyOptimizer({ runner() }, journal)

    val gamingEngine = GamingEngine(dailyOptimizer, { runner() }, PrivilegeMode.SHIZUKU)

    val rootLab = RootLab({ runner() }, journal)

    /**
     * Re-probe privilege state. Root probing performs a real `su` call which
     * may prompt the root manager - that's intentional and user-visible.
     */
    suspend fun refreshPrivileges(promptRoot: Boolean = false): PrivilegeState =
        withContext(Dispatchers.Default) {
            val shizukuAlive = shizukuGate.isBinderAlive()
            val shizukuAuth = shizukuGate.hasPermission()
            val preV11 = shizukuGate.isPreV11()

            var rootAvail = false
            var rootOk = false
            try {
                val probe = RootGate.probe()
                rootAvail = probe != null || rootManagerInstalled()
                rootOk = probe != null
                if (rootOk) rootRunner = probe
            } catch (_: Throwable) {
                rootAvail = rootManagerInstalled()
            }

            val state = PrivilegeDetector.compose(
                context = appContext,
                shizukuBinderAlive = shizukuAlive,
                shizukuPermissionGranted = shizukuAuth,
                shizukuPreV11 = preV11,
                rootAvailable = rootAvail,
                rootGranted = rootOk,
            )
            privilegeState = state
            state
        }

    private fun rootManagerInstalled(): Boolean =
        PrivilegeDetector.detectRootManager(appContext) !=
            com.eis.silentoptimizer.privilege.RootManager.NONE

    fun requestShizukuPermission() = shizukuGate.requestPermission()

    /** Collects all components for UI reporting. */
    fun componentReport(): Map<String, String> {
        val s = privilegeState
        return linkedMapOf(
            "Mode" to s.mode.name,
            "Shizuku" to when {
                !s.shizukuInstalled -> "not installed"
                !s.shizukuRunning -> "installed, not running"
                !s.shizukuAuthorized -> "running, unauthorized"
                else -> "authorized"
            },
            "Root" to when {
                s.rootGranted -> "granted (${s.rootManager})"
                s.rootAvailable -> "available, not granted"
                else -> "unavailable"
            },
            "Journal" to "${journal.entries().size} entries",
        )
    }

    fun init() {
        RootGate.init()
        shizukuGate.register()
        shizukuGate.onStateChanged = { /* state refresh happens on next refreshPrivileges() */ }
    }

    fun shutdown() {
        shizukuGate.unregister()
        shizukuGate.disconnectUserService()
    }
}
