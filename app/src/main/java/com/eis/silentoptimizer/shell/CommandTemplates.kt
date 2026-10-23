package com.eis.silentoptimizer.shell

import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.core.Validation

/**
 * Command execution layer (spec 12).
 *
 * Every command reaches the device through one of two runners (Shizuku shell
 * or root). Runners refuse to execute anything that is not built by
 * [CommandTemplates], and [CommandTemplates] refuses to build anything whose
 * interpolated values fail [Validation]. There is no raw-string command API
 * exposed to the UI.
 */
interface CommandRunner {
    val mode: PrivilegeMode
    suspend fun exec(command: String): OpResult
}

/**
 * Canonical, validated command builders. Each function returns null when an
 * input fails validation, and callers must treat null as "do not run".
 */
object CommandTemplates {

    // --- Package management -------------------------------------------------

    /** Reversible first-choice action: disable for the current user. */
    fun disableUser(packageName: String): String? =
        if (Validation.isValidPackageName(packageName))
            "pm disable-user --user 0 $packageName"
        else null

    fun enableUser(packageName: String): String? =
        if (Validation.isValidPackageName(packageName))
            "pm enable --user 0 $packageName"
        else null

    /** Uninstall updates / system app rollback for current user (reversible via install-existing). */
    fun uninstallUser(packageName: String): String? =
        if (Validation.isValidPackageName(packageName))
            "pm uninstall --user 0 $packageName"
        else null

    /** Restore a package previously uninstalled for the current user. */
    fun installExisting(packageName: String): String? =
        if (Validation.isValidPackageName(packageName))
            "cmd package install-existing --user 0 $packageName"
        else null

    fun uninstallUpdates(packageName: String): String? =
        if (Validation.isValidPackageName(packageName))
            "pm uninstall-system-updates --user 0 $packageName"
        else null

    /** Read-only queries. */
    fun listPackages(systemOnly: Boolean, userId: Int = 0): String =
        if (systemOnly) "pm list packages -s --user $userId" else "pm list packages --user $userId"

    fun listDisabledPackages(userId: Int = 0): String = "pm list packages -d --user $userId"

    fun packageState(packageName: String): String? =
        if (Validation.isValidPackageName(packageName))
            "pm list packages -d -e $packageName"
        else null

    fun dumpPackage(packageName: String): String? =
        if (Validation.isValidPackageName(packageName))
            "dumpsys package $packageName"
        else null

    // --- Settings -----------------------------------------------------------

    fun settingsGet(namespace: String, key: String): String? {
        if (namespace !in setOf("system", "secure", "global")) return null
        if (!Validation.isValidSettingsKey(key)) return null
        return "settings get $namespace $key"
    }

    fun settingsPut(namespace: String, key: String, value: String): String? {
        if (namespace !in setOf("system", "secure", "global")) return null
        if (!Validation.isValidSettingsKey(key)) return null
        if (!Validation.isValidSettingsValue(value)) return null
        return "settings put $namespace $key $value"
    }

    /** Animation scales live in the global namespace (spec 7). */
    fun animationScale(which: String, scale: Float): String? {
        val key = when (which) {
            "window" -> "window_animation_scale"
            "transition" -> "transition_animation_scale"
            "animator" -> "animator_duration_scale"
            else -> return null
        }
        val value = when (scale) {
            0f -> "0.0"; 0.5f -> "0.5"; 1f -> "1.0"; 1.5f -> "1.5"
            2f -> "2.0"; 5f -> "5.0"; 10f -> "10.0"; else -> return null
        }
        return settingsPut("global", key, value)
    }

    /** Refresh-rate policy on builds that honor these keys (spec 6). */
    fun setPeakRefreshRate(hz: Int): String? =
        if (Validation.isValidRefreshRate(hz)) settingsPut("system", "peak_refresh_rate", hz.toString())
        else null

    fun clearPeakRefreshRate(): String = "settings delete system peak_refresh_rate"

    // --- Sysfs reads (Root Lab, read-only by default) ------------------------

    fun readNode(path: String): String? =
        if (Validation.isAllowedSysfsPath(path)) "cat $path" else null

    fun readDir(path: String): String? =
        if (Validation.isAllowedSysfsPath(path) && !path.contains(" ")) "ls -la $path" else null

    /**
     * The ONLY sysfs write template. Deliberately narrow: single-node writes
     * to CPU/GPU devfreq targets, validated against an allowlist of value
     * patterns. Root Lab UI never lets users type arbitrary strings.
     */
    fun writeNode(path: String, value: String): String? {
        if (!Validation.isAllowedSysfsPath(path)) return null
        if (path.contains("..")) return null
        // Values: integers, governor names, short alphanumeric tokens.
        if (!Regex("^[A-Za-z0-9._-]{1,64}$").matches(value)) return null
        // Writing to procfs critical files is never allowed.
        if (path.startsWith("/proc/sys/kernel/") &&
            (path.contains("panic") || path.contains("sysrq") || path.contains("modprobe"))
        ) return null
        return "echo $value > $path"
    }

    // --- Root manager module queries -----------------------------------------

    fun listMagiskModules(): String = "ls /data/adb/modules"
    fun listKernelSuModules(): String = "ls /data/adb/ksu/modules"
    fun readModuleProp(moduleId: String): String? =
        if (Regex("^[A-Za-z0-9._-]{1,64}$").matches(moduleId))
            "cat /data/adb/modules/$moduleId/module.prop"
        else null

    // --- Misc ----------------------------------------------------------------

    fun uptime(): String = "cat /proc/uptime"
    fun cpuInfo(): String = "cat /proc/cpuinfo"
    fun memInfo(): String = "cat /proc/meminfo"
    fun version(): String = "cat /proc/version"

    fun whoami(): String = "id"

    /**
     * Last-gate check used by runners: the outgoing command must match one of
     * the known shapes. Defense in depth against a future caller bypassing
     * CommandTemplates.
     */
    private val ALLOWED_PATTERNS = listOf(
        Regex("^pm (disable-user|enable|uninstall|uninstall-system-updates|list packages)[a-zA-Z0-9 ._\\-]*$"),
        Regex("^cmd package (install-existing|list)[a-zA-Z0-9 ._\\-]*$"),
        Regex("^dumpsys package [a-zA-Z0-9._]+$"),
        Regex("^settings (get|put|delete) (system|secure|global) [a-zA-Z0-9_.]+( [a-zA-Z0-9._:/\\-]+)?$"),
        Regex("^cat (/sys|/proc)[a-zA-Z0-9._/\\-]*$"),
        Regex("^ls -la (/sys|/proc|/data/adb)[a-zA-Z0-9._/\\-]*$"),
        Regex("^echo [A-Za-z0-9._\\-]{1,64} > (/sys)[a-zA-Z0-9._/\\-]*$"),
        Regex("^ls (/data/adb/(modules|ksu/modules))$"),
        Regex("^id$"),
        Regex("^getprop( [a-zA-Z0-9._\\-]+)?$"),
    )

    fun isAllowedCommand(command: String): Boolean =
        ALLOWED_PATTERNS.any { it.matches(command) } && !command.contains("&&") && !command.contains(";")
}
