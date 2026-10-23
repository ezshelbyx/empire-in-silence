package com.eis.silentoptimizer.rootlab

import com.eis.silentoptimizer.core.ChangeRecord
import com.eis.silentoptimizer.core.JournalStore
import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.core.RiskLevel
import com.eis.silentoptimizer.shell.CommandRunner
import com.eis.silentoptimizer.shell.CommandTemplates

/**
 * Advanced Root Optimization Lab (spec 8).
 *
 * Read-first policy: every control shows current value, supported values
 * (when the node exposes them), required privilege, risk, persistence and
 * reset procedure BEFORE it lets anyone write. Writes go through
 * CommandTemplates.writeNode only - single node, validated value, no
 * chaining. Nothing critical (thermal throttling, SELinux, verified boot,
 * panics) is ever writable through EIS.
 */
data class SysfsControl(
    val path: String,
    val label: String,
    val currentValue: String?,
    val supportedValues: List<String>?,   // null = node doesn't enumerate them
    val requiredPrivilege: PrivilegeMode,
    val expectedEffect: String,
    val risk: RiskLevel,
    val persistence: String,              // e.g. "lost on reboot", "module-dependent"
    val rebootRequired: Boolean,
    val resetProcedure: String,
    val writable: Boolean,
)

data class ThermalReading(val zone: String, val celsius: Double?)

class RootLab(
    private val runnerProvider: () -> CommandRunner?,
    private val journal: JournalStore? = null,
) {

    private suspend fun read(path: String): String? {
        val cmd = CommandTemplates.readNode(path) ?: return null
        val runner = runnerProvider() ?: return null
        val res = runner.exec(cmd)
        return if (res.success && res.stdout.isNotBlank()) res.stdout.trim() else null
    }

    // --- Inspectors (all read-only) ------------------------------------------

    suspend fun cpuGovernors(): List<SysfsControl> {
        val runner = runnerProvider() ?: return emptyList()
        val controls = mutableListOf<SysfsControl>()

        // Enumerate online CPUs (cpu0..cpuN).
        val cpuCount = (0..15).count { cpu ->
            runner.exec(CommandTemplates.readNode("/sys/devices/system/cpu/cpu$cpu/online") ?: "x")
                .let { it.success || it.stdout.contains("can't") } // cpu0 has no online node
        }.coerceAtLeast(1)

        for (i in 0 until minOf(cpuCount, 8)) {
            val dir = "/sys/devices/system/cpu/cpu$i/cpufreq"
            val governor = read("$dir/scaling_governor")
            val available = read("$dir/scaling_available_governors")
            val curFreq = read("$dir/scaling_cur_freq")
            val minFreq = read("$dir/scaling_min_freq")
            val maxFreq = read("$dir/scaling_max_freq")
            val cpuMax = read("$dir/cpuinfo_max_freq")
            val cpuMin = read("$dir/cpuinfo_min_freq")

            if (governor == null && available == null) continue
            controls += SysfsControl(
                path = "$dir/scaling_governor",
                label = "CPU$i governor",
                currentValue = governor ?: "unreadable",
                supportedValues = available?.split(Regex("\\s+"))?.filter { it.isNotBlank() },
                requiredPrivilege = PrivilegeMode.ROOT,
                expectedEffect = "Governor decides how CPU frequency scales under load. " +
                    "No governor is universally faster or more efficient; defaults are OEM-tuned.",
                risk = RiskLevel.HIGH_RISK,
                persistence = "Reset to default on reboot (sysfs is not persistent).",
                rebootRequired = false,
                resetProcedure = "Write back the OEM default or reboot.",
                writable = true,
            )
            if (curFreq != null || minFreq != null || maxFreq != null) {
                controls += SysfsControl(
                    path = "$dir/scaling_cur_freq",
                    label = "CPU$i frequency",
                    currentValue = "${curFreq ?: "?"} (min=$minFreq max=$maxFreq cpuinfo=$cpuMin..$cpuMax kHz)",
                    supportedValues = null,
                    requiredPrivilege = PrivilegeMode.ROOT,
                    expectedEffect = "Current/supported frequency window. Read-only view.",
                    risk = RiskLevel.SAFE,
                    persistence = "n/a (read-only)",
                    rebootRequired = false,
                    resetProcedure = "n/a",
                    writable = false,
                )
            }
        }
        return controls
    }

    suspend fun gpuControls(): List<SysfsControl> {
        val runner = runnerProvider() ?: return emptyList()
        val controls = mutableListOf<SysfsControl>()

        // devfreq devices: GPU paths vary by SoC (msm_adreno_tsr, mali, g12b, etc.)
        val ls = runner.exec(CommandTemplates.readDir("/sys/class/devfreq") ?: "x")
        val devices = ls.stdout.lines()
            .map { it.trim().split(Regex("\\s+")).lastOrNull() ?: "" }
            .filter { it.isNotBlank() && !it.startsWith(".") && it != "total" }

        for (dev in devices) {
            if (!Regex("^[A-Za-z0-9._-]+$").matches(dev)) continue
            val base = "/sys/class/devfreq/$dev"
            val governor = read("$base/governor")
            val avail = read("$base/available_governors")
            val cur = read("$base/cur_freq")
            val min = read("$base/min_freq")
            val max = read("$base/max_freq")
            val devMax = read("$base/max_freq")
            val interesting = listOf("gpu", "mali", "adreno", "kgsl", "freq", "devfreq")
            if (interesting.none { dev.lowercase().contains(it) }) continue
            controls += SysfsControl(
                path = "$base/governor",
                label = "GPU $dev governor",
                currentValue = governor ?: "unreadable",
                supportedValues = avail?.split(Regex("\\s+"))?.filter { it.isNotBlank() },
                requiredPrivilege = PrivilegeMode.ROOT,
                expectedEffect = "GPU devfreq governor. Overclocking via these nodes is NOT supported by EIS: " +
                    "exceeding cpuinfo/max_freq is blocked and unsafe.",
                risk = RiskLevel.HIGH_RISK,
                persistence = "Lost on reboot.",
                rebootRequired = false,
                resetProcedure = "Restore previous governor value or reboot.",
                writable = true,
            )
            if (cur != null) {
                controls += SysfsControl(
                    path = "$base/cur_freq",
                    label = "GPU $dev frequency",
                    currentValue = "$cur (min=$min max=$devMax)",
                    supportedValues = null,
                    requiredPrivilege = PrivilegeMode.ROOT,
                    expectedEffect = "Current GPU frequency window (read-only).",
                    risk = RiskLevel.SAFE,
                    persistence = "n/a (read-only)",
                    rebootRequired = false,
                    resetProcedure = "n/a",
                    writable = false,
                )
            }
        }
        return controls
    }

    suspend fun thermalZones(): List<ThermalReading> {
        val runner = runnerProvider() ?: return emptyList()
        val ls = runner.exec("ls /sys/class/thermal") ?: return emptyList()
        val out = mutableListOf<ThermalReading>()
        for (name in ls.stdout.lines().filter { it.startsWith("thermal_zone") }.take(32)) {
            if (!Regex("^[A-Za-z0-9_]+$").matches(name)) continue
            val type = read("/sys/class/thermal/$name/type")
            val temp = read("/sys/class/thermal/$name/temp")?.toLongOrNull()
            if (temp != null) {
                val c = if (temp > 1000) temp / 1000.0 else temp.toDouble()
                out += ThermalReading(type ?: name, c)
            }
        }
        return out
    }

    suspend fun zramState(): Map<String, String> {
        val keys = listOf(
            "disksize" to "/sys/block/zram0/disksize",
            "comp_algorithm" to "/sys/block/zram0/comp_algorithm",
            "mem_used_total" to "/sys/block/zram0/mem_used_total",
        )
        val out = mutableMapOf<String, String>()
        for ((label, path) in keys) {
            read(path)?.let { out[label] = it }
        }
        read("/proc/meminfo")?.let { mem ->
            for (line in mem.lines()) {
                if (line.startsWith("SwapTotal") || line.startsWith("SwapFree") ||
                    line.startsWith("MemTotal") || line.startsWith("MemAvailable")
                ) {
                    out[line.substringBefore(":").trim()] = line.substringAfter(":").trim()
                }
            }
        }
        return out
    }

    suspend fun ioScheduler(): Map<String, String> {
        val runner = runnerProvider() ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for (block in listOf("sda", "mmcblk0")) {
            read("/sys/block/$block/queue/scheduler")?.let { out[block] = it }
        }
        return out
    }

    suspend fun kernelInfo(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        read("/proc/version")?.let { out["version"] = it }
        read("/proc/cmdline")?.let { out["cmdline"] = it }
        return out
    }

    /**
     * The ONLY write path in Root Lab. Rejects when: not root, path not
     * allowed, value malformed, node unreadable (not validated), or the
     * current value already equals the target. Dry-run returns the exact
     * command without executing.
     */
    suspend fun writeControl(
        path: String,
        value: String,
        previousValue: String?,
        dryRun: Boolean,
    ): OpResult {
        val cmd = CommandTemplates.writeNode(path, value)
            ?: return OpResult.validationFailed("sysfs write to $path")
        if (dryRun) return OpResult(true, null, message = "DRY RUN: $cmd", command = cmd)

        val runner = runnerProvider() ?: return OpResult.notAvailable("Sysfs write", PrivilegeMode.ROOT)
        if (runner.mode != PrivilegeMode.ROOT) {
            return OpResult(
                false, null,
                message = "Sysfs writes require root. Current mode: ${runner.mode}.",
                command = cmd,
            )
        }
        // Read-validate before write (spec 8).
        val current = read(path)
        if (current == null) {
            return OpResult(
                false, null,
                message = "Target node is not readable on this device - refusing to write blindly.",
                command = cmd,
            )
        }
        val res = runner.exec(cmd)
        if (res.success) {
            val now = read(path)
            if (now != null && now != value && now.trim() != value) {
                journal?.record(failRecord(path, current, null, cmd, "read-back mismatch"))
                return OpResult(
                    false, res.exitCode,
                    message = "Write accepted but read-back is '$now' (expected '$value'). " +
                        "Kernel or vendor driver rejected the effective change.",
                    command = cmd,
                )
            }
            journal?.record(okRecord(path, current, value, cmd))
        } else {
            journal?.record(failRecord(path, current, null, cmd, res.message))
        }
        return res
    }

    private fun okRecord(path: String, previous: String, new: String, cmd: String) = ChangeRecord(
        id = "sysfs-${System.currentTimeMillis()}-${path.hashCode()}",
        timestampMillis = System.currentTimeMillis(),
        action = "sysfs-write",
        target = path,
        previousValue = previous,
        newValue = new,
        mode = com.eis.silentoptimizer.core.PrivilegeMode.ROOT,
        dryRun = false,
        success = true,
        message = "Applied",
        undoCommand = undoCommandFor(path, previous),
    )

    private fun failRecord(path: String, previous: String?, new: String?, cmd: String, msg: String) = ChangeRecord(
        id = "sysfs-${System.currentTimeMillis()}-${path.hashCode()}",
        timestampMillis = System.currentTimeMillis(),
        action = "sysfs-write",
        target = path,
        previousValue = previous,
        newValue = new,
        mode = com.eis.silentoptimizer.core.PrivilegeMode.ROOT,
        dryRun = false,
        success = false,
        message = msg,
        undoCommand = null,
    )

    fun undoCommandFor(path: String, previousValue: String?): String? =
        previousValue?.let { CommandTemplates.writeNode(path, it) }
}
