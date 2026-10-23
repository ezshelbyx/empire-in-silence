package com.eis.silentoptimizer.privilege

import android.content.Context
import android.content.pm.PackageManager as AndroidPackageManager
import com.eis.silentoptimizer.core.PrivilegeMode

/**
 * Runtime privilege detection (spec 4).
 *
 * The app NEVER assumes the device is rooted or that Shizuku exists. Each
 * tier is detected independently and the highest usable tier is chosen:
 *   ROOT > SHIZUKU > STANDARD.
 */

enum class RootManager { MAGISK, KERNELSU, APATCH, SUPERUSER, UNKNOWN, NONE }

data class PrivilegeState(
    val mode: PrivilegeMode,
    val shizukuInstalled: Boolean,
    val shizukuRunning: Boolean,
    val shizukuAuthorized: Boolean,
    val rootAvailable: Boolean,
    val rootGranted: Boolean,
    val rootManager: RootManager,
    val notes: List<String>,
) {
    val canRunShell: Boolean get() = mode != PrivilegeMode.STANDARD
    val canWriteSystem: Boolean get() = mode == PrivilegeMode.ROOT
}

object PrivilegeDetector {

    private val ROOT_MANAGER_PACKAGES = mapOf(
        "com.topjohnwu.magisk" to RootManager.MAGISK,
        "me.weishu.kernelsu" to RootManager.KERNELSU,
        "me.bmax.apatch" to RootManager.APATCH,
        "com.koushikdutta.superuser" to RootManager.SUPERUSER,
        "com.thirdparty.superuser" to RootManager.SUPERUSER,
        "eu.chainfire.supersu" to RootManager.SUPERUSER,
    )

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: AndroidPackageManager.NameNotFoundException) {
        false
    }

    /**
     * Detect which root management app is present. This only reports the
     * manager app being installed; whether root is actually granted is
     * determined separately by ShellFactory probing su.
     */
    fun detectRootManager(context: Context): RootManager {
        for ((pkg, manager) in ROOT_MANAGER_PACKAGES) {
            if (isPackageInstalled(context, pkg)) return manager
        }
        return RootManager.NONE
    }

    fun shizukuInstalled(context: Context): Boolean =
        isPackageInstalled(context, SHIZUKU_PACKAGE)

    /** Pure package -> manager mapping (unit-tested). */
    fun rootManagerForPackage(packageName: String): RootManager =
        ROOT_MANAGER_PACKAGES[packageName] ?: RootManager.NONE

    /**
     * Compose the current privilege state from Shizuku binder status and the
     * root probe result. [rootGranted] must come from an actual su probe - we
     * never claim root based on a manager app being installed.
     */
    fun compose(
        context: Context,
        shizukuBinderAlive: Boolean,
        shizukuPermissionGranted: Boolean,
        shizukuPreV11: Boolean,
        rootAvailable: Boolean,
        rootGranted: Boolean,
    ): PrivilegeState = composeState(
        shizukuInstalled = shizukuInstalled(context),
        rootManager = detectRootManager(context),
        shizukuBinderAlive = shizukuBinderAlive,
        shizukuPermissionGranted = shizukuPermissionGranted,
        shizukuPreV11 = shizukuPreV11,
        rootAvailable = rootAvailable,
        rootGranted = rootGranted,
    )

    /** Pure decision function (unit-tested): same logic, no Android deps. */
    fun composeState(
        shizukuInstalled: Boolean,
        rootManager: RootManager,
        shizukuBinderAlive: Boolean,
        shizukuPermissionGranted: Boolean,
        shizukuPreV11: Boolean,
        rootAvailable: Boolean,
        rootGranted: Boolean,
    ): PrivilegeState {
        val notes = mutableListOf<String>()

        if (!shizukuInstalled) {
            notes += "Shizuku app not installed. Mode B unavailable."
        } else if (!shizukuBinderAlive) {
            notes += "Shizuku installed but not running. Start it via wireless debugging or ADB; " +
                "it must be restarted after every reboot."
        } else if (shizukuPreV11) {
            notes += "Shizuku version too old (pre-v11). Update the Shizuku app."
        } else if (!shizukuPermissionGranted) {
            notes += "Shizuku running but EIS is not authorized yet. Grant permission in the Shizuku prompt."
        }

        val effectiveRootManager = if (rootGranted) rootManager else RootManager.NONE
        if (rootAvailable && !rootGranted) {
            notes += "Root binary found but EIS has not been granted superuser access. " +
                "Approve the request in ${rootManager.name.lowercase().replaceFirstChar { it.uppercase() }}."
        }
        if (!rootAvailable) {
            notes += "No su binary detected. Root Lab stays locked."
        }

        val mode = when {
            rootAvailable && rootGranted -> PrivilegeMode.ROOT
            shizukuInstalled && shizukuBinderAlive && !shizukuPreV11 && shizukuPermissionGranted ->
                PrivilegeMode.SHIZUKU
            else -> PrivilegeMode.STANDARD
        }

        return PrivilegeState(
            mode = mode,
            shizukuInstalled = shizukuInstalled,
            shizukuRunning = shizukuBinderAlive,
            shizukuAuthorized = shizukuBinderAlive && shizukuPermissionGranted && !shizukuPreV11,
            rootAvailable = rootAvailable,
            rootGranted = rootGranted,
            rootManager = effectiveRootManager,
            notes = notes,
        )
    }
}
