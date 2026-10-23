package com.eis.silentoptimizer.privilege

import android.util.Log
import com.eis.silentoptimizer.shell.RootCommandRunner
import com.topjohnwu.superuser.Shell

/**
 * Root integration (spec 4, Mode C).
 *
 * Supports Magisk, KernelSU and APatch through their shared contract: a
 * working `su` binary on PATH. APatch compatibility is detected separately
 * (manager package `me.bmax.apatch`) and reported as "APatch detected -
 * unverified" rather than assumed identical, per spec 4.
 *
 * All requests go through the installed root manager's own authorization
 * dialog; we never pre-grant, cache, or bypass it.
 */
object RootGate {

    private const val TAG = "EIS-RootGate"

    /** Configure libsu once. App processes only; no root shells are cached. */
    fun init() {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(20),
        )
    }

    /** Actual effective-privilege probe (uid=0), not a package-presence guess. */
    suspend fun probe(): RootCommandRunner? {
        val runner = RootCommandRunner()
        return if (runner.probe()) runner else null
    }

    /**
     * Detect the root manager flavor from su version strings that managers
     * conventionally answer with. Purely informational for the UI.
     */
    suspend fun describeManager(): String = try {
        val out = Shell.cmd("su -v").exec()
        val version = out.out.joinToString(" ").trim()
        when {
            version.contains("KernelSU", true) || version.contains("ksu", true) -> "KernelSU $version"
            version.contains("APatch", true) || version.contains("apd", true) -> "APatch $version"
            version.contains("Magisk", true) -> "Magisk $version"
            version.isNotBlank() -> version
            else -> "su (manager unknown)"
        }
    } catch (t: Throwable) {
        Log.i(TAG, "describeManager failed", t)
        "su (manager unknown)"
    }

    /**
     * Module inventory: prefers the detected manager's own directory and
     * falls back to the other known paths. Read-only, no downloads, no
     * installs ever initiated from here (spec 8).
     */
    suspend fun listModules(): List<RootModule> {
        val paths = listOf(
            "/data/adb/modules" to "magisk",       // Magisk and KernelSU-compatible path
            "/data/adb/ksu/modules" to "kernelsu",
            "/data/adb/ap/modules" to "apatch",
        )
        val found = mutableListOf<RootModule>()
        for ((path, flavor) in paths) {
            val list = Shell.cmd("ls $path").exec()
            if (!list.isSuccess) continue
            for (id in list.out.filter { it.isNotBlank() }) {
                if (!Regex("^[A-Za-z0-9._-]+$").matches(id)) continue
                val prop = Shell.cmd("cat $path/$id/module.prop").exec()
                val lines = prop.out
                val name = lines.firstOrNull { it.startsWith("name=") }?.substringAfter("=") ?: id
                val version = lines.firstOrNull { it.startsWith("version=") }?.substringAfter("=") ?: "?"
                val enabled = !Shell.cmd("test -f $path/$id/disable").exec().isSuccess
                found += RootModule(
                    id = id, name = name, version = version,
                    flavor = flavor, enabled = enabled,
                    path = "$path/$id",
                )
            }
        }
        return found.distinctBy { it.path }
    }
}

data class RootModule(
    val id: String,
    val name: String,
    val version: String,
    val flavor: String,
    val enabled: Boolean,
    val path: String,
)
