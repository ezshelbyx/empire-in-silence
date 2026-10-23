package com.eis.silentoptimizer.privilege

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager as AndroidPackageManager
import android.os.IBinder
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import com.eis.silentoptimizer.BuildConfig
import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Shizuku integration (spec 4, Mode B).
 *
 * Follows the official Shizuku API guide:
 *  - check pingBinder / isPreV11 before any call
 *  - register binder-received / binder-dead listeners
 *  - request permission through Shizuku.requestPermission
 *  - prefer UserService for structured operations (package state)
 *
 * Shizuku generally runs as ADB shell (uid 2000). It must be started via
 * wireless debugging or `adb sh /sdcard/Android/data/moe.shizuku...` after
 * every reboot - we surface that to the user instead of pretending it
 * persists.
 */
class ShizukuGate(private val context: Context) {

    var onStateChanged: (() -> Unit)? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        onStateChanged?.invoke()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        onStateChanged?.invoke()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        onStateChanged?.invoke()
    }

    fun register() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun unregister() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun isPreV11(): Boolean = try {
        isBinderAlive() && Shizuku.isPreV11()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isBinderAlive() &&
            Shizuku.checkSelfPermission() == AndroidPackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun shouldShowRationale(): Boolean = try {
        isBinderAlive() && Shizuku.shouldShowRequestPermissionRationale()
    } catch (_: Throwable) {
        false
    }

    /** Never bypasses authorization - simply forwards to the Shizuku prompt. */
    fun requestPermission(requestCode: Int = REQUEST_CODE) {
        try {
            if (isBinderAlive() && !isPreV11()) Shizuku.requestPermission(requestCode)
        } catch (_: Throwable) {
            // Shizuku not reachable; state listener will report it.
        }
    }

    // --- UserService ---------------------------------------------------------

    private var userService: IUserService? = null
    private var userServiceConnection: android.content.ServiceConnection? = null

    /**
     * Bind the UserService (runs with shell identity in a separate process).
     * The UserService exposes only `exec(command)` - an allowlisted shell
     * runner - so we never touch hidden framework APIs that SDK builds
     * cannot link against.
     */
    suspend fun connectUserService(): OpResult = withContext(Dispatchers.IO) {
        if (userService != null) return@withContext OpResult(true, 0, message = "Already connected")
        if (!isBinderAlive() || !hasPermission()) {
            return@withContext OpResult.notAvailable("UserService", PrivilegeMode.SHIZUKU)
        }
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, EisUserService::class.java.name),
            )
                .daemon(false)
                .processNameSuffix("eis-ops")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)

            val connected = withTimeoutOrNull(10_000L) {
                suspendCancellableCoroutine<IUserService> { cont: CancellableContinuation<IUserService> ->
                    val conn = object : android.content.ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (cont.isActive) {
                                cont.resumeWith(Result.success(IUserService.Stub.asInterface(service)))
                            }
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {
                            userService = null
                            if (cont.isActive) {
                                cont.resumeWith(Result.failure(IllegalStateException("UserService disconnected")))
                            }
                        }
                    }
                    userServiceConnection = conn
                    Shizuku.bindUserService(args, conn)
                    cont.invokeOnCancellation {
                        try { Shizuku.unbindUserService(args, conn, true) } catch (_: Throwable) {}
                    }
                    Unit
                }
            }
            if (connected != null) {
                userService = connected
                OpResult(true, 0, message = "UserService connected")
            } else {
                OpResult(false, null, message = "UserService bind timed out")
            }
        } catch (t: Throwable) {
            OpResult(false, null, message = "UserService bind failed: ${t.message}")
        }
    }

    fun disconnectUserService() {
        val conn = userServiceConnection ?: return
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, EisUserService::class.java.name),
            ).daemon(false).processNameSuffix("eis-ops").debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
            Shizuku.unbindUserService(args, conn, true)
        } catch (_: Throwable) {}
        userService = null
        userServiceConnection = null
    }

    /**
     * Execute one allowlisted command as Shizuku's shell identity. This is
     * the single primitive every privileged Shizuku operation is built on;
     * `pm`/`settings`/`dumpsys` behave exactly as they do over `adb shell`.
     */
    suspend fun execCommand(command: String): OpResult = withContext(Dispatchers.IO) {
        if (!com.eis.silentoptimizer.shell.CommandTemplates.isAllowedCommand(command)) {
            return@withContext OpResult.validationFailed("command '$command'")
        }
        connectUserService().let { if (!it.success) return@withContext it }
        try {
            val svc = userService ?: return@withContext OpResult(false, null, message = "UserService unavailable")
            val out = svc.exec(command)
            OpResult(true, 0, stdout = out.trim(), message = "OK", command = command)
        } catch (t: Throwable) {
            OpResult(false, null, message = "UserService call failed: ${t.message}", command = command)
        }
    }

    /**
     * Set enabled state for a package through an allowlisted `pm` command.
     * Shell/root identity supports `pm disable-user`/`pm enable` directly;
     * this avoids the hidden IPackageManager binder API entirely.
     */
    suspend fun setApplicationEnabled(
        packageName: String,
        newState: Int,
        userId: Int = 0,
    ): OpResult {
        if (!com.eis.silentoptimizer.core.Validation.isValidPackageName(packageName)) {
            return OpResult.validationFailed("package name")
        }
        val cmd = when (newState) {
            AndroidPackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ->
                com.eis.silentoptimizer.shell.CommandTemplates.disableUser(packageName)
            AndroidPackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            AndroidPackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            -> com.eis.silentoptimizer.shell.CommandTemplates.enableUser(packageName)
            else -> null
        } ?: return OpResult(false, null, message = "Unsupported enabled-state request ($newState).")
        return execCommand(cmd)
    }

    suspend fun getApplicationEnabled(packageName: String, userId: Int = 0): Int? {
        if (!com.eis.silentoptimizer.core.Validation.isValidPackageName(packageName)) return null
        val cmd = com.eis.silentoptimizer.shell.CommandTemplates.packageState(packageName) ?: return null
        val res = execCommand(cmd)
        if (!res.success) return null
        return if (res.stdout.contains(packageName))
            AndroidPackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        else AndroidPackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    }

    fun identity(): String = try {
        if (userService != null) userService?.identity ?: "connected"
        else if (hasPermission()) "shell (permission granted)" else "not connected"
    } catch (_: Throwable) {
        "unavailable"
    }

    companion object {
        const val REQUEST_CODE = 0xE15
    }
}
