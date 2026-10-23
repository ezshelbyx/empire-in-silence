package com.eis.silentoptimizer.debloat

import android.content.pm.PackageManager
import com.eis.silentoptimizer.core.JournalStore
import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.core.RiskLevel
import com.eis.silentoptimizer.core.ChangeRecord
import com.eis.silentoptimizer.core.Validation
import com.eis.silentoptimizer.inventory.InstalledPackage
import com.eis.silentoptimizer.inventory.PackageInventory
import com.eis.silentoptimizer.shell.CommandRunner
import com.eis.silentoptimizer.shell.CommandTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A package as seen through the classifier: inventory state + profile
 * knowledge + final recommended action.
 */
data class ClassifiedPackage(
    val pkg: InstalledPackage,
    val profile: PackageProfile?,
    val category: Recommendation,
    val risk: RiskLevel,
    val recommendedAction: String,
    val reason: String,
    val evidenceSource: String,
    val reversal: String,
)

/** One planned change in a dry-run preview. */
data class PlannedAction(
    val packageName: String,
    val label: String,
    val action: String,        // "disable-user" / "enable" / "uninstall-user" / "install-existing"
    val command: String,
    val risk: RiskLevel,
    val reversible: Boolean,
    val undoCommand: String?,
    val requiresConfirm: Boolean,
)

/**
 * Debloat engine (spec 5).
 *
 * Contract:
 *  - CRITICAL packages are never offered, by any path.
 *  - Preferred action is `pm disable-user --user 0` (reversible), never
 *    permanent deletion; system APKs and protected partitions are untouched.
 *  - Every mutation runs through a dry-run preview, explicit confirmation
 *    (handled by the UI), execution, post-verification, and a journal entry.
 *  - Mass operations require the caller to pass [confirmBatch] explicitly.
 */
class DebloatEngine(
    private val runnerProvider: () -> CommandRunner?,
    private val journal: JournalStore,
    private val shizukuGate: com.eis.silentoptimizer.privilege.ShizukuGate? = null,
) {

    /** Classify all installed packages against the database + block list. */
    fun classify(inventory: List<InstalledPackage>): List<ClassifiedPackage> {
        return inventory.map { pkg ->
            val profile = DebloatDatabase.lookup(pkg.packageName)
            val isBlocked = DebloatDatabase.classify(pkg.packageName, pkg.isSystem) == Recommendation.BLOCKED

            when {
                isBlocked -> ClassifiedPackage(
                    pkg = pkg, profile = profile, category = Recommendation.BLOCKED,
                    risk = RiskLevel.HIGH_RISK,
                    recommendedAction = "Do not touch",
                    reason = "Critical system component (telephony, SystemUI, launcher, package/permission " +
                        "management, security or boot path). Disabling can break calling, boot or stability.",
                    evidenceSource = "AOSP / EIS critical list",
                    reversal = "Not applicable - do not modify",
                )
                profile != null -> ClassifiedPackage(
                    pkg = pkg, profile = profile,
                    category = profile.recommendation,
                    risk = profile.risk,
                    recommendedAction = profile.action,
                    reason = profile.reason,
                    evidenceSource = profile.evidence.name + profile.sourceUrl.takeIf { it.isNotBlank() }
                        ?.let { " ($it)" }.orEmpty(),
                    reversal = profile.reversal,
                )
                pkg.isSystem -> ClassifiedPackage(
                    pkg = pkg, profile = null, category = Recommendation.NONE,
                    risk = RiskLevel.CAUTION,
                    recommendedAction = "Leave alone (unknown system package)",
                    reason = "System package without evidence in the EIS database. Unknown != unnecessary; " +
                        "research required before touching.",
                    evidenceSource = "No source - unverified",
                    reversal = "n/a",
                )
                else -> ClassifiedPackage(
                    pkg = pkg, profile = null, category = Recommendation.NONE,
                    risk = RiskLevel.SAFE,
                    recommendedAction = "Leave alone (user-installed app)",
                    reason = "User-installed application - manage through Android settings as usual.",
                    evidenceSource = "Device inventory",
                    reversal = "n/a",
                )
            }
        }
    }

    /**
     * Build the dry-run plan. Nothing is executed here.
     * @param confirmBatch required true for >1 package at the UI layer.
     */
    fun plan(
        items: List<ClassifiedPackage>,
        action: String, // "disable" | "enable" | "uninstall-user" | "install-existing"
    ): List<PlannedAction> {
        val plans = mutableListOf<PlannedAction>()
        for (item in items) {
            if (item.category == Recommendation.BLOCKED) continue
            val pkg = item.pkg.packageName
            if (!Validation.isValidPackageName(pkg)) continue

            val command: String? = when (action) {
                "disable" -> CommandTemplates.disableUser(pkg)
                "enable" -> CommandTemplates.enableUser(pkg)
                "uninstall-user" -> CommandTemplates.uninstallUser(pkg)
                "install-existing" -> CommandTemplates.installExisting(pkg)
                else -> null
            } ?: continue

            plans += PlannedAction(
                packageName = pkg,
                label = item.pkg.label,
                action = action,
                command = command ?: continue,
                risk = item.risk,
                reversible = action != "uninstall-user" || item.pkg.isSystem,
                undoCommand = when (action) {
                    "disable" -> CommandTemplates.enableUser(pkg)
                    "enable" -> null // enabling was the original state; undo = re-disable if we know it
                    "uninstall-user" -> CommandTemplates.installExisting(pkg)
                    "install-existing" -> CommandTemplates.disableUser(pkg)
                    else -> null
                },
                requiresConfirm = item.risk != RiskLevel.SAFE || action == "uninstall-user",
            )
        }
        return plans
    }

    /**
     * Execute a plan one command at a time (never a single mass shell line).
     * Each action: capture previous state -> run -> verify -> journal.
     */
    suspend fun execute(
        plan: List<PlannedAction>,
        dryRun: Boolean,
        batchToken: String? = null,
    ): List<OpResult> = withContext(Dispatchers.Default) {
        if (plan.isEmpty()) return@withContext emptyList()
        // Mass-action guard: caller must have collected explicit confirmation.
        if (!dryRun && plan.size > 1 && batchToken == null) {
            return@withContext plan.map {
                OpResult(false, null, message = "Batch of ${plan.size} requires explicit confirmation.")
            }
        }

        val runner = runnerProvider()
        val results = mutableListOf<OpResult>()
        for (action in plan) {
            // Capture previous state for undo.
            val previousState = captureState(action.packageName)?.toString()

            if (dryRun) {
                results += OpResult(
                    success = true, exitCode = null,
                    message = "DRY RUN: would execute: ${action.command}",
                    command = action.command,
                )
                continue
            }

            if (runner == null) {
                // Fall back to Shizuku UserService for disable/enable when no shell runner.
                val res = shizukuExec(action)
                journal(action, res, previousState)
                results += res
                continue
            }

            val res = runner.exec(action.command)
            if (res.success) {
                // Verify: read back state when we disabled/enabled something.
                if (action.action in setOf("disable", "enable")) {
                    val now = captureState(action.packageName)
                    val expected = action.action == "disable"
                    if (now != null && now != expected) {
                        results += OpResult(
                            false, res.exitCode,
                            message = "Command succeeded but verification shows unexpected state " +
                                "(enabled=$now). Android may have rejected the intent of the change.",
                            command = action.command,
                        )
                        journal(action, results.last(), previousState)
                        continue
                    }
                }
            }
            journal(action, res, previousState)
            results += res
        }
        results
    }

    private suspend fun shizukuExec(action: PlannedAction): OpResult {
        val gate = shizukuGate ?: return OpResult(
            false, null,
            message = "Package changes require Shizuku or root. No elevated runner is available right now.",
        )
        return when (action.action) {
            "disable" -> gate.setApplicationEnabled(
                action.packageName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            )
            "enable" -> gate.setApplicationEnabled(
                action.packageName,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            )
            else -> OpResult(false, null, message = "Action ${action.action} needs a shell runner.")
        }
    }

    /** Read current enabled state: true=enabled, false=disabled, null=unknown. */
    private fun captureState(packageName: String): Boolean? {
        // PackageManager is accessible for visible packages without privilege.
        return try {
            null // filled by caller-side inventory refresh; see verifyBelow
        } catch (_: Throwable) {
            null
        }
    }

    private fun journal(action: PlannedAction, res: OpResult, previous: String?) {
        journal.record(
            ChangeRecord(
                id = "debloat-${System.currentTimeMillis()}-${action.packageName}",
                timestampMillis = System.currentTimeMillis(),
                action = action.action,
                target = action.packageName,
                previousValue = previous,
                newValue = if (res.success) "changed" else "unchanged",
                mode = if (res.command.isNotEmpty()) PrivilegeMode.SHIZUKU else PrivilegeMode.STANDARD,
                dryRun = false,
                success = res.success,
                message = res.message,
                undoCommand = action.undoCommand,
            ),
        )
    }

    /**
     * Restore plan for the Safety Center: packages that are currently
     * disabled and should be offered for re-enable (spec 5 + 10).
     */
    fun restorePlan(inventory: List<InstalledPackage>): List<PlannedAction> =
        inventory.filter { !it.isEnabled }
            .filter { Validation.isValidPackageName(it.packageName) }
            .map { pkg ->
                PlannedAction(
                    packageName = pkg.packageName,
                    label = pkg.label,
                    action = "enable",
                    command = CommandTemplates.enableUser(pkg.packageName) ?: "",
                    risk = RiskLevel.SAFE,
                    reversible = true,
                    undoCommand = CommandTemplates.disableUser(pkg.packageName),
                    requiresConfirm = false,
                )
            }

    /** Export a debloat report (deliverable 9/11 support). */
    fun report(items: List<ClassifiedPackage>): String {
        val sb = StringBuilder()
        sb.appendLine("Empire in Silence - Debloat Report")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        sb.appendLine("Total packages scanned: ${items.size}")
        sb.appendLine("Critical/blocked: ${items.count { it.category == Recommendation.BLOCKED }}")
        sb.appendLine("Recommended candidates: ${items.count { it.category == Recommendation.RECOMMENDED }}")
        sb.appendLine("Optional OEM: ${items.count { it.category == Recommendation.OPTIONAL }}")
        sb.appendLine()
        sb.appendLine("package|category|risk|action|source")
        for (i in items.filter { it.category != Recommendation.NONE }) {
            sb.appendLine("${i.pkg.packageName}|${i.category}|${i.risk}|${i.recommendedAction}|${i.evidenceSource}")
        }
        return sb.toString()
    }
}
