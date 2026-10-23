package com.eis.silentoptimizer.shell

import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.core.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes validated commands under Shizuku's shell identity.
 *
 * Shizuku runs this under the ADB shell identity (uid 2000), so `pm`,
 * `settings`, `dumpsys` and read-only `cat` all work exactly as they do
 * over `adb shell`. `Shizuku.newProcess` was made private in API 13.1.5,
 * so we go through the public UserService binder transport and run the
 * command inside that shell-identity process. This keeps us on supported
 * public API while still executing allowlisted commands as shell.
 */
class ShizukuCommandRunner(
    private val gate: com.eis.silentoptimizer.privilege.ShizukuGate,
) : CommandRunner {

    override val mode: PrivilegeMode = PrivilegeMode.SHIZUKU

    private fun ensureAvailable(): OpResult? {
        val ok = gate.isBinderAlive() && !gate.isPreV11() && gate.hasPermission()
        return if (ok) null else OpResult.notAvailable("Shell command", PrivilegeMode.SHIZUKU)
    }

    override suspend fun exec(command: String): OpResult = withContext(Dispatchers.IO) {
        ensureAvailable()?.let { return@withContext it }

        if (!CommandTemplates.isAllowedCommand(command)) {
            return@withContext OpResult.validationFailed("command '$command'")
        }

        val timeoutMs = 30_000L
        try {
            // Delegate to the gate, which runs the command inside a
            // shell-identity UserService over the public binder API.
            val res = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                gate.execCommand(command)
            }
            res ?: OpResult(
                success = false, exitCode = null,
                message = "Command timed out after ${timeoutMs / 1000}s.",
                command = Validation.sanitizeForDisplay(command),
            )
        } catch (t: Throwable) {
            OpResult(
                success = false, exitCode = null,
                message = "Shizuku execution failed: ${t.message ?: t.javaClass.simpleName}",
                command = Validation.sanitizeForDisplay(command),
            )
        }
    }
}
