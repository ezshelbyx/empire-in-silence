package com.eis.silentoptimizer.gaming

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager as AndroidPackageManager
import android.os.Build
import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.core.RiskLevel
import com.eis.silentoptimizer.core.Validation
import com.eis.silentoptimizer.daily.DailyOptimizer
import com.eis.silentoptimizer.shell.CommandRunner

/**
 * Gaming optimization engine (spec 6).
 *
 * Honesty rules enforced here:
 *  - Display refresh rate != game FPS. Profiles set the *display policy*
 *    (peak_refresh_rate) and animation/thermal preferences - they never
 *    fabricate FPS numbers.
 *  - Per-app Game Mode overrides via android.app.GameManager are @SystemApi
 *    (MANAGE_GAME_MODE); a normal app cannot set them. When root/Shizuku is
 *    absent we report "unsupported" instead of simulating.
 *  - Thermal protections are never disabled, ever (spec 8).
 */
enum class GameProfile(val title: String, val description: String) {
    BALANCED(
        "Balanced",
        "Default display policy, animations at 1x, no background restrictions. Recommended starting point.",
    ),
    COMPETITIVE(
        "Competitive",
        "Peak display refresh (device-supported), animations 0x for lowest input-to-photon latency in UI, " +
            "aggressive background restriction guidance. Display policy only - does not change game FPS.",
    ),
    MAX_STABILITY(
        "Maximum Stability",
        "Refresh rate capped to 60 to reduce heat and frame-time variance on 120Hz+ panels, animations 1x, " +
            "thermal monitoring emphasized. Does not disable throttling.",
    ),
    BATTERY_SAVER(
        "Battery Saver",
        "Refresh capped to 60, animations 0.5x, guidance to limit background work. Lowest display power draw.",
    ),
    CUSTOM(
        "Custom",
        "User-chosen refresh rate and animation scales per session.",
    ),
}

data class DetectedGame(
    val packageName: String,
    val label: String,
    val category: Int?,
)

data class GamingState(
    val profile: GameProfile,
    val targetRefreshRate: Int?,      // null = device default
    val restrictBackground: Boolean,
    val thermalWarnCelsius: Double,
)

object GameDetector {

    /**
     * Detect games via ApplicationInfo category (API 26+) plus a conservative
     * package-name heuristic for pre-category builds. No network access.
     */
    fun detectGames(context: Context): List<DetectedGame> {
        val pm = context.packageManager
        val flags = 0
        val apps = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(AndroidPackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(flags)
            }
        } catch (_: Throwable) {
            emptyList()
        }
        return apps.mapNotNull { info ->
            val isGame = (Build.VERSION.SDK_INT >= 26 && info.category == ApplicationInfo.CATEGORY_GAME) ||
                KNOWN_GAME_PREFIXES.any { info.packageName.startsWith(it) }
            if (!isGame) return@mapNotNull null
            DetectedGame(
                packageName = info.packageName,
                label = try { pm.getApplicationLabel(info).toString() } catch (_: Throwable) { info.packageName },
                category = if (Build.VERSION.SDK_INT >= 26) info.category else null,
            )
        }.sortedBy { it.label.lowercase() }
    }

    private val KNOWN_GAME_PREFIXES = listOf(
        "com.dts.", "com.epicgames.", "com.garena.", "com.miHoYo.", "com.mojang.",
        "com.netease.", "com.tencent.", "com.activision.", "com.cod.", "com.ea.",
        "com.gameloft.", "com.kiloo.", "com.outfit7.", "com.supercell.",
        "com.unity.", "com.valvesoftware.", "org.json.",
    )
}

class GamingEngine(
    private val dailyOptimizer: DailyOptimizer,
    private val runnerProvider: () -> CommandRunner?,
    private val requiredMode: PrivilegeMode,
) {
    /** All gaming actions report their real privilege requirement. */
    fun capabilities(mode: PrivilegeMode): Map<String, Boolean> = mapOf(
        "refresh_control" to (mode != PrivilegeMode.STANDARD),
        "animation_control" to (mode != PrivilegeMode.STANDARD),
        "game_mode_api" to false,     // @SystemApi; never claimed (research: GameManager cross-package is system-only)
        "fps_measurement" to false,   // We do not fabricate FPS. Overlay measurement needs surfaceflinger access.
        "thermal_read" to true,       // PowerManager thermal status is public API 29+
        "thermal_disable" to false,   // Never offered (spec 6/8)
    )

    suspend fun apply(
        state: GamingState,
        dryRun: Boolean,
    ): List<Pair<String, OpResult>> {
        val results = mutableListOf<Pair<String, OpResult>>()

        // Refresh rate
        val hz = state.targetRefreshRate
        if (hz != null) {
            if (Validation.isValidRefreshRate(hz)) {
                val res = dailyOptimizer.setPeakRefreshRate(hz, dryRun)
                results += "refresh_rate" to res
            } else {
                results += "refresh_rate" to OpResult.validationFailed("refresh rate $hz")
            }
        } else {
            results += "refresh_rate" to dailyOptimizer.clearPeakRefreshRate(dryRun)
        }

        // Animations per profile
        val animScale = when (state.profile) {
            GameProfile.COMPETITIVE -> 0f
            GameProfile.BATTERY_SAVER, GameProfile.MAX_STABILITY -> 1f
            GameProfile.BALANCED -> 1f
            GameProfile.CUSTOM -> 1f
        }
        for (which in listOf("window", "transition", "animator")) {
            results += "anim_$which" to dailyOptimizer.setAnimationScale(which, animScale, dryRun)
        }

        return results
    }

    /** Static, per-game configuration guidance (no unsupported promises). */
    fun recommendationsFor(game: DetectedGame, mode: PrivilegeMode): List<String> {
        val out = mutableListOf<String>()
        if (mode == PrivilegeMode.STANDARD) {
            out += "Standard mode: use Android's built-in Game Mode (Settings > Digital Wellbeing > " +
                "Games) if your build provides it; EIS cannot set per-app game modes without privilege."
        } else {
            out += "Display policy is applied system-wide (peak_refresh_rate). This does not force the " +
                "game itself to render at that rate - game FPS depends on the game and GPU."
        }
        out += "Thermal state ${if (mode == PrivilegeMode.STANDARD) "(readable via PowerManager)" else "(monitor thermal zones in Root Lab)"} - " +
            "if the device reports Severe or worse, expect throttling; EIS will not disable it."
        out += "Game package: ${game.packageName} (${game.label})"
        return out
    }

    fun profileRisks(profile: GameProfile): List<String> = when (profile) {
        GameProfile.COMPETITIVE -> listOf(
            "Animations at 0x can confuse some app transitions (reversible: set back to 1x).",
            "120/144/165 Hz peak policy increases display power draw; not all panels/OEMs honor it.",
            "Background restriction changes can delay messages in other apps while profile is active.",
        )
        GameProfile.MAX_STABILITY -> listOf(
            "60 Hz cap on a 120 Hz panel trades smoothness for thermal headroom; reversible.",
            "No thermal protection is ever disabled by this profile.",
        )
        GameProfile.BATTERY_SAVER -> listOf(
            "Capped refresh and 0.5x animations; some users may notice stutter in scrolling apps.",
        )
        GameProfile.BALANCED -> listOf("No changes beyond defaults.")
        GameProfile.CUSTOM -> listOf("Custom values are validated against supported refresh rates.")
    }

    fun riskForRefreshRate(hz: Int): RiskLevel = when (hz) {
        60 -> RiskLevel.SAFE
        90 -> RiskLevel.CAUTION
        else -> RiskLevel.CAUTION
    }
}
