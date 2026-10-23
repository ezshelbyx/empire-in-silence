package com.eis.silentoptimizer.core

import kotlinx.serialization.Serializable

/**
 * Core result and model types shared across all modules.
 *
 * Design rule (spec 3): no operation may claim success unless Android
 * actually accepted it. Every privileged operation returns [OpResult] with
 * the real exit code and captured output.
 */

/** Which privilege tier the app is currently operating under. */
@Serializable
enum class PrivilegeMode {
    STANDARD,   // no elevated access at all
    SHIZUKU,    // ADB/shell identity via Shizuku
    ROOT,       // full root via Magisk/KernelSU/APatch su
}

/** Risk classification for any proposed change (spec 11). */
enum class RiskLevel { SAFE, CAUTION, HIGH_RISK }

/** Result of a single executed (or attempted) operation. */
data class OpResult(
    val success: Boolean,
    val exitCode: Int?,
    val stdout: String = "",
    val stderr: String = "",
    val message: String = "",
    val command: String = "",
) {
    companion object {
        fun notAvailable(what: String, mode: PrivilegeMode) = OpResult(
            success = false,
            exitCode = null,
            message = "$what requires ${mode.name.lowercase().replaceFirstChar { it.uppercase() }} privileges. " +
                "Current mode: ${mode.name.lowercase().replaceFirstChar { it.uppercase() }}.",
        )

        fun validationFailed(what: String) = OpResult(
            success = false,
            exitCode = null,
            message = "Input validation failed for $what. Operation not executed.",
        )
    }
}

/** Why an operation is blocked when it cannot run. */
enum class BlockReason {
    NONE,
    NO_PRIVILEGE,
    VALIDATION_FAILED,
    DEVICE_INCOMPATIBLE,
    COMMAND_REJECTED,
    USER_DECLINED,
}
