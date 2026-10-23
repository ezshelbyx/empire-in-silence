package com.eis.silentoptimizer.daily

import com.eis.silentoptimizer.core.ChangeRecord
import com.eis.silentoptimizer.core.JournalStore
import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.core.RiskLevel
import com.eis.silentoptimizer.shell.CommandRunner
import com.eis.silentoptimizer.shell.CommandTemplates

/**
 * Daily Optimization engine (spec 7).
 *
 * Every operation states its required privilege and degrades honestly:
 * without Shizuku/root the UI links the user to the corresponding system
 * settings page instead of pretending to write settings.
 */
data class DailyOp(
    val id: String,
    val title: String,
    val detail: String,
    val requiredMode: PrivilegeMode,
    val risk: RiskLevel,
    val reversible: Boolean,
)

object DailyOps {

    val all = listOf(
        DailyOp(
            "anim_window", "Window animation scale 0.5x",
            "Halves window animation duration. Feel-based improvement only; no benchmark claim. " +
                "Reversible by restoring 1.0x.",
            PrivilegeMode.SHIZUKU, RiskLevel.SAFE, reversible = true,
        ),
        DailyOp(
            "anim_transition", "Transition animation scale 0.5x",
            "Halves activity transition animation duration. Reversible.",
            PrivilegeMode.SHIZUKU, RiskLevel.SAFE, reversible = true,
        ),
        DailyOp(
            "anim_animator", "Animator duration scale 0.5x",
            "Halves animator duration globally. Some apps assume 1x timing; restore if UI feels rushed.",
            PrivilegeMode.SHIZUKU, RiskLevel.SAFE, reversible = true,
        ),
        DailyOp(
            "refresh_reset", "Release refresh-rate cap",
            "Deletes the peak_refresh_rate override so the display policy returns to default. " +
                "Only applies on builds that honor this key (AOSP multiple-refresh-rate policy).",
            PrivilegeMode.SHIZUKU, RiskLevel.SAFE, reversible = true,
        ),
        DailyOp(
            "standby_check", "App standby diagnostics",
            "Reads app standby buckets where Usage Access is granted; read-only, no privilege needed beyond usage access.",
            PrivilegeMode.STANDARD, RiskLevel.SAFE, reversible = true,
        ),
        DailyOp(
            "battery_check", "Battery health snapshot",
            "Reads charge level, temperature, health and current via BatteryManager. Read-only.",
            PrivilegeMode.STANDARD, RiskLevel.SAFE, reversible = true,
        ),
    )
}

/**
 * Executes daily operations through the active runner.
 * Returns the actual OpResult - failures are surfaced verbatim.
 */
class DailyOptimizer(
    private val runnerProvider: () -> CommandRunner?,
    private val journal: JournalStore? = null,
) {

    private fun journalChange(
        action: String,
        target: String,
        previous: String?,
        new: String?,
        command: String,
        success: Boolean,
        message: String,
        undo: String?,
    ) {
        journal?.record(
            ChangeRecord(
                id = "daily-${System.currentTimeMillis()}-${action}-${target}",
                timestampMillis = System.currentTimeMillis(),
                action = action,
                target = target,
                previousValue = previous,
                newValue = new,
                mode = com.eis.silentoptimizer.core.PrivilegeMode.SHIZUKU,
                dryRun = false,
                success = success,
                message = message,
                undoCommand = undo,
            ),
        )
    }

    suspend fun setAnimationScale(which: String, scale: Float, dryRun: Boolean): OpResult {
        val cmd = CommandTemplates.animationScale(which, scale)
            ?: return OpResult.validationFailed("animation scale $scale for $which")
        if (dryRun) return OpResult(true, null, message = "DRY RUN: $cmd", command = cmd)
        val runner = runnerProvider() ?: return OpResult.notAvailable("Animation scale", PrivilegeMode.SHIZUKU)
        val previous = readAnimationScale(which)
        val res = runner.exec(cmd)
        if (res.success) {
            val undo = previous?.toFloatOrNull()?.let { CommandTemplates.animationScale(which, it) }
            journalChange("set-animation", which, previous, scale.toString(), cmd, true, res.message, undo)
        } else {
            journalChange("set-animation", which, previous, null, cmd, false, res.message, null)
        }
        return res
    }

    suspend fun readAnimationScale(which: String): String? {
        val key = when (which) {
            "window" -> "window_animation_scale"
            "transition" -> "transition_animation_scale"
            "animator" -> "animator_duration_scale"
            else -> return null
        }
        val cmd = CommandTemplates.settingsGet("global", key) ?: return null
        val runner = runnerProvider() ?: return null
        val res = runner.exec(cmd)
        return if (res.success) res.stdout.trim().takeIf { it != "null" } else null
    }

    suspend fun setPeakRefreshRate(hz: Int, dryRun: Boolean): OpResult {
        val cmd = CommandTemplates.setPeakRefreshRate(hz)
            ?: return OpResult.validationFailed("refresh rate $hz")
        if (dryRun) return OpResult(true, null, message = "DRY RUN: $cmd", command = cmd)
        val runner = runnerProvider() ?: return OpResult.notAvailable("Refresh rate", PrivilegeMode.SHIZUKU)
        val previous = readRefreshRateSetting()
        val res = runner.exec(cmd)
        return if (res.success) {
            // Verify the value actually stuck - OEM builds may ignore it.
            val readBack = runner.exec(CommandTemplates.settingsGet("system", "peak_refresh_rate") ?: "")
            val applied = readBack.stdout.trim() == hz.toString()
            if (applied) {
                val undo = previous?.let { CommandTemplates.settingsPut("system", "peak_refresh_rate", it) }
                    ?: CommandTemplates.clearPeakRefreshRate()
                journalChange("set-refresh-rate", "peak_refresh_rate", previous, hz.toString(),
                    cmd, true, "OK", undo)
                res
            } else {
                journalChange("set-refresh-rate", "peak_refresh_rate", previous, null, cmd, false,
                    "OEM ignored the setting", null)
                OpResult(
                    false, res.exitCode,
                    message = "Command accepted but read-back is '${readBack.stdout.trim()}', not $hz. " +
                        "This OEM build appears to ignore peak_refresh_rate; no change in effect.",
                    command = cmd,
                )
            }
        } else res
    }

    suspend fun clearPeakRefreshRate(dryRun: Boolean): OpResult {
        val cmd = CommandTemplates.clearPeakRefreshRate()
        if (dryRun) return OpResult(true, null, message = "DRY RUN: $cmd", command = cmd)
        val runner = runnerProvider() ?: return OpResult.notAvailable("Refresh rate", PrivilegeMode.SHIZUKU)
        return runner.exec(cmd)
    }

    suspend fun readRefreshRateSetting(): String? {
        val cmd = CommandTemplates.settingsGet("system", "peak_refresh_rate") ?: return null
        val runner = runnerProvider() ?: return null
        val res = runner.exec(cmd)
        return if (res.success) res.stdout.trim().takeIf { it != "null" } else null
    }
}
