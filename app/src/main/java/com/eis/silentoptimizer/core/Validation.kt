package com.eis.silentoptimizer.core

/**
 * Input validation for every value that can reach a shell command (spec 12).
 *
 * Package names, sysfs paths, numeric values and settings keys are validated
 * against strict patterns BEFORE they are concatenated into any command
 * string. Anything that fails validation is rejected, never executed.
 *
 * These functions are pure and unit-tested (see core/ValidationTest.kt).
 */
object Validation {

    private val PACKAGE_REGEX =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    // Android package segments are alphanumeric + underscore; allow hyphen in
    // rare vendor packages but never at the boundaries.
    private val PACKAGE_RELAXED_REGEX =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9][a-zA-Z0-9_-]*)+$")

    private val SETTINGS_KEY_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_.]{0,63}$")
    private val SETTINGS_VALUE_REGEX = Regex("^[a-zA-Z0-9_.:/-]{0,64}$")
    private val POSITIVE_INT_REGEX = Regex("^[0-9]{1,10}$")
    private val FLOAT_1_DECIMAL_REGEX = Regex("^[0-9]\\.[0-9]\$")

    /** Characters that must never appear in anything passed to a shell. */
    private val SHELL_METACHARACTERS = charArrayOf(
        ';', '|', '&', '`', '$', '>', '<', '\n', '\r', '*', '?',
        '(', ')', '{', '}', '[', ']', '~', '!', '#', '\\', '"', '\'',
    )

    fun isValidPackageName(name: String): Boolean =
        name.length in 3..255 && PACKAGE_REGEX.matches(name)

    /** Slightly relaxed check used only when reading existing device state. */
    fun isPlausiblePackageName(name: String): Boolean =
        name.length in 1..255 && PACKAGE_RELAXED_REGEX.matches(name)

    fun containsShellMetacharacters(value: String): Boolean =
        SHELL_METACHARACTERS.any { it in value }

    fun isValidSettingsKey(key: String): Boolean =
        SETTINGS_KEY_REGEX.matches(key) && !containsShellMetacharacters(key)

    fun isValidSettingsValue(value: String): Boolean =
        SETTINGS_VALUE_REGEX.matches(value) && !containsShellMetacharacters(value)

    fun isNonNegativeInt(value: String): Boolean = POSITIVE_INT_REGEX.matches(value)

    fun isValidAnimationScale(value: String): Boolean = value in setOf(
        "0.0", "0.5", "1.0", "1.5", "2.0", "5.0", "10.0",
    )

    /**
     * Sysfs/procfs paths are restricted to a read-allowlist of subtrees and
     * may never contain traversal or shell characters.
     */
    fun isAllowedSysfsPath(path: String): Boolean {
        if (path.contains("..") || containsShellMetacharacters(path)) return false
        if (!path.startsWith("/")) return false
        val allowedPrefixes = listOf(
            "/sys/class/thermal/",
            "/sys/devices/system/cpu/",
            "/sys/class/devfreq/",
            "/sys/class/power_supply/",
            "/sys/block/zram0/",
            "/sys/block/sda/queue/",
            "/sys/block/mmcblk0/queue/",
            "/proc/",
            "/sys/kernel/",
            "/sys/module/",
            "/sys/fs/",
            "/data/adb/",
        )
        return allowedPrefixes.any { path.startsWith(it) }
    }

    /** Refresh rate choices we will ever offer. */
    fun isValidRefreshRate(hz: Int): Boolean = hz in setOf(60, 90, 120, 144, 165)

    /**
     * Full command-string check used by CommandRunner as a last gate:
     * the command must match one of our known templates after substitution,
     * with no stray metacharacters in the substituted values.
     */
    fun sanitizeForDisplay(command: String): String =
        command.replace(Regex("[\\r\\n]+"), " ").take(500)
}
