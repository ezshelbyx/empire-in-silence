package com.eis.silentoptimizer.privilege

import android.util.Log
import com.eis.silentoptimizer.shell.CommandTemplates

/**
 * Shizuku UserService implementation.
 *
 * Runs in a separate process with Shizuku's ADB-shell identity (uid 2000).
 * It exposes exactly one privileged operation: `exec(command)` for an
 * allowlisted command. Because shell identity already has `pm`, `settings`
 * and `dumpsys` access, we run them with plain [ProcessBuilder] - no hidden
 * framework APIs (IPackageManager/ServiceManager), which SDK-compiled code
 * cannot link against.
 *
 * Every command is re-checked against the same allowlist the app-side
 * runners use ([CommandTemplates.isAllowedCommand]), so a compromised
 * binder caller cannot smuggle arbitrary shell through this process.
 */
class EisUserService : IUserService.Stub() {

    override fun exec(command: String): String {
        if (!CommandTemplates.isAllowedCommand(command)) {
            Log.w(TAG, "Rejected non-allowlisted command")
            return ""
        }
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            Log.i(TAG, "exec($command) -> exit=$code stderr=$stderr")
            stdout
        } catch (t: Throwable) {
            Log.e(TAG, "exec failed for $command", t)
            ""
        }
    }

    override fun getIdentity(): String = try {
        "uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}"
    } catch (_: Throwable) {
        "unknown"
    }

    companion object {
        private const val TAG = "EIS-UserService"
    }
}
