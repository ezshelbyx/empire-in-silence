package com.eis.silentoptimizer.shell

import com.topjohnwu.superuser.Shell
import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.core.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes validated commands as root via libsu (Magisk/KernelSU/APatch all
 * provide a compatible `su`).
 *
 * Root commands are the narrowest in the app: they must pass
 * [CommandTemplates] allowlisting plus libsu's own shell. No free-form user
 * input ever reaches this runner.
 */
class RootCommandRunner : CommandRunner {

    override val mode: PrivilegeMode = PrivilegeMode.ROOT

    /**
     * Probe whether root is actually granted right now (spec 4: check
     * effective privileges before execution).
     */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("id").exec()
            result.isSuccess && result.out.any { it.contains("uid=0") }
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun exec(command: String): OpResult = withContext(Dispatchers.IO) {
        if (!CommandTemplates.isAllowedCommand(command)) {
            return@withContext OpResult.validationFailed("command '$command'")
        }
        try {
            val result = Shell.cmd(command).exec()
            OpResult(
                success = result.isSuccess,
                exitCode = result.code,
                stdout = result.out.joinToString("\n"),
                stderr = result.err.joinToString("\n"),
                message = when {
                    result.isSuccess -> "OK"
                    else -> "Command rejected (exit ${result.code})"
                },
                command = Validation.sanitizeForDisplay(command),
            )
        } catch (t: Throwable) {
            OpResult(
                success = false, exitCode = null,
                message = "Root execution failed: ${t.message ?: t.javaClass.simpleName}",
                command = Validation.sanitizeForDisplay(command),
            )
        }
    }
}

/**
 * Standard (unprivileged) runner. It can still read world-readable nodes
 * like /proc and /sys/class/thermal; anything requiring privilege returns
 * an honest failure from Android rather than a fake success.
 */
class StandardCommandRunner : CommandRunner {

    override val mode: PrivilegeMode = PrivilegeMode.STANDARD

    override suspend fun exec(command: String): OpResult = withContext(Dispatchers.IO) {
        if (!CommandTemplates.isAllowedCommand(command)) {
            return@withContext OpResult.validationFailed("command '$command'")
        }
        // Unprivileged app processes cannot fork arbitrary shell on modern
        // Android; we only attempt reads of world-readable proc/sys files
        // through direct file IO, never through `sh`.
        val readPath = command.removePrefix("cat ").takeIf {
            command.startsWith("cat /") && it.startsWith("/proc")
        }
        if (readPath != null) {
            return@withContext try {
                val file = java.io.File(readPath)
                val text = if (file.canRead()) file.readText() else ""
                OpResult(
                    success = text.isNotEmpty(), exitCode = if (text.isNotEmpty()) 0 else 1,
                    stdout = text.trim(), message = if (text.isNotEmpty()) "OK" else "Not readable without privilege",
                    command = Validation.sanitizeForDisplay(command),
                )
            } catch (t: Throwable) {
                OpResult(success = false, exitCode = 1, message = "Read failed: ${t.message}",
                    command = Validation.sanitizeForDisplay(command))
            }
        }
        OpResult.notAvailable("Shell commands", PrivilegeMode.STANDARD)
    }
}
