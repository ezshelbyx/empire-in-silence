package com.eis.silentoptimizer.inventory

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager as AndroidPackageManager
import android.os.Build
import com.eis.silentoptimizer.core.Validation

/**
 * Package inventory (spec 5).
 *
 * Scans installed packages through PackageManager. On Android 11+ package
 * visibility filtering applies, so without QUERY_ALL_PACKAGES some packages
 * are invisible - we flag this honestly in the UI when the count looks
 * filtered (we can't enumerate what we cannot see, but we can detect the
 * discrepancy via `pm list packages` when a privileged mode is active).
 */
data class InstalledPackage(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val isStopped: Boolean,
    val uid: Int,
    val apkSizeBytes: Long?,
    val dataSizeBytes: Long?,
    val sourceDir: String,
    val category: Int?,
    val installerPackageName: String?,
    val permissions: List<String>,
    val enabledState: Int,
)

object PackageInventory {

    @Suppress("DEPRECATION")
    fun scan(context: Context, includePermissions: Boolean = false): List<InstalledPackage> {
        val pm = context.packageManager
        val flags = AndroidPackageManager.GET_META_DATA
        val packages: List<android.content.pm.PackageInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(AndroidPackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getInstalledPackages(flags)
            }
        } catch (_: Throwable) {
            emptyList()
        }

        return packages.mapNotNull { pi ->
            val info = pi.applicationInfo ?: return@mapNotNull null
            if (!Validation.isPlausiblePackageName(pi.packageName)) return@mapNotNull null
            val enabledState: Int = try {
                pm.getApplicationEnabledSetting(pi.packageName)
            } catch (_: Throwable) {
                if (info.enabled) AndroidPackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else AndroidPackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            InstalledPackage(
                packageName = pi.packageName,
                label = try {
                    pm.getApplicationLabel(info).toString()
                } catch (_: Throwable) {
                    pi.packageName
                },
                versionName = pi.versionName,
                isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isEnabled = enabledState != AndroidPackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
                    enabledState != AndroidPackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                    enabledState != AndroidPackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
                isStopped = (info.flags and ApplicationInfo.FLAG_STOPPED) != 0,
                uid = info.uid,
                apkSizeBytes = try {
                    java.io.File(info.sourceDir).length().takeIf { it > 0 }
                } catch (_: Throwable) { null },
                dataSizeBytes = null, // requires privilege; filled in opportunistically
                sourceDir = info.sourceDir ?: "",
                category = if (Build.VERSION.SDK_INT >= 26) info.category else null,
                installerPackageName = try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        pm.getInstallSourceInfo(pi.packageName).installingPackageName
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstallerPackageName(pi.packageName)
                    }
                } catch (_: Throwable) { null },
                permissions = if (includePermissions) {
                    try {
                        if (Build.VERSION.SDK_INT >= 33) {
                            pm.getPackageInfo(
                                pi.packageName,
                                AndroidPackageManager.PackageInfoFlags.of(
                                    AndroidPackageManager.GET_PERMISSIONS.toLong(),
                                ),
                            ).requestedPermissions?.toList() ?: emptyList()
                        } else {
                            pm.getPackageInfo(pi.packageName, AndroidPackageManager.GET_PERMISSIONS)
                                .requestedPermissions?.toList() ?: emptyList()
                        }
                    } catch (_: Throwable) { emptyList() }
                } else emptyList(),
                enabledState = enabledState,
            )
        }.sortedWith(compareBy<InstalledPackage> { pkg: InstalledPackage -> pkg.label.lowercase() })
    }

    /**
     * Packages currently disabled for user 0 - the basis of the restore plan
     * (spec 5: re-enable previously disabled packages).
     */
    fun disabledPackages(context: Context): List<InstalledPackage> =
        scan(context).filter { !it.isEnabled }
}
