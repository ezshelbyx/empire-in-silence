package com.eis.silentoptimizer.recommend

import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.core.RiskLevel
import com.eis.silentoptimizer.device.DeviceProfile
import com.eis.silentoptimizer.device.OemSkin

/**
 * Smart Optimization Recommendations (spec 9).
 *
 * Pure, device-aware rules. Every recommendation carries: problem addressed,
 * evidence (source), compatibility assessment, expected benefit, risk,
 * required permission level, reversal instructions and a confidence rating
 * based on evidence quality. No numerical performance claims are ever
 * invented - benefits are qualitative unless a source quantifies them.
 */
enum class Priority {
    GAMING_STABILITY,
    EVERYDAY_RESPONSIVENESS,
    BATTERY_LIFE,
    STORAGE_SPACE,
    PRIVACY,
    ADVANCED_SYSTEM_CONTROL,
}

enum class Confidence { HIGH, MEDIUM, LOW }

data class RecItem(
    val id: String,
    val title: String,
    val problem: String,
    val evidence: String,
    val evidenceUrl: String,
    val compatibility: String,
    val expectedBenefit: String,
    val risk: RiskLevel,
    val requiredMode: PrivilegeMode,
    val reversal: String,
    val confidence: Confidence,
    val priorities: Set<Priority>,
)

data class RecInput(
    val profile: DeviceProfile,
    val mode: PrivilegeMode,
    val priorities: Set<Priority>,
    val installedPackages: Set<String>,
    val availableStorageBytes: Long,
    val totalStorageBytes: Long,
    val thermalStatus: Int,          // PowerManager thermal status, -1 = unknown
    val animationScaleKnown: Boolean,
    val animationScale: Double?,     // current global animator scale if known
)

object RecommendationEngine {

    fun evaluate(input: RecInput): List<RecItem> {
        val items = mutableListOf<RecItem>()

        // --- Storage -------------------------------------------------------
        if (input.totalStorageBytes > 0) {
            val freeRatio = input.availableStorageBytes.toDouble() / input.totalStorageBytes
            if (freeRatio < 0.10) {
                items += RecItem(
                    id = "storage_low",
                    title = "Free up storage (under 10% free)",
                    problem = "Only ${"%.1f".format(freeRatio * 100)}% of storage is free. Low free space " +
                        "degrades performance (f2fs/ext4 GC stalls), blocks OTA updates and can cause app crashes.",
                    evidence = "AOSP storage guidance: apps should keep adequate free space; " +
                        "Android triggers low-storage actions below threshold.",
                    evidenceUrl = "https://developer.android.com/training/data-storage",
                    compatibility = "All devices. Read-only measurement; cleanup is manual.",
                    expectedBenefit = "Restores headroom for updates and app data; avoids low-storage churn. " +
                        "No specific speed claim.",
                    risk = RiskLevel.SAFE,
                    requiredMode = PrivilegeMode.STANDARD,
                    reversal = "N/A - no system change performed.",
                    confidence = Confidence.HIGH,
                    priorities = setOf(Priority.STORAGE_SPACE, Priority.EVERYDAY_RESPONSIVENESS),
                )
            }
        }

        // --- Debloat (Shizuku or root) --------------------------------------
        if (input.mode != PrivilegeMode.STANDARD &&
            (Priority.PRIVACY in input.priorities || Priority.STORAGE_SPACE in input.priorities)
        ) {
            val hasKnownBloat = KNOWN_BLOAT.any { it in input.installedPackages }
            items += RecItem(
                id = "debloat_review",
                title = if (hasKnownBloat) "Review recommended disable candidates" else "Audit OEM packages",
                problem = "Preloaded services can run background work and collect telemetry even when unused.",
                evidence = "UAD (community) lists + EIS evidence database; bulk removal has caused bootloops, " +
                    "so EIS proposes one-at-a-time reversible disables only.",
                evidenceUrl = "https://github.com/0x192/universal-android-debloater",
                compatibility = "${input.profile.manufacturer} ${input.profile.model} / " +
                    "${input.profile.oemSkin} profile; requires ${input.mode.name.lowercase()}.",
                expectedBenefit = "Removes unused background services and telemetry; storage effect is small " +
                    "(system APKs stay on system partition when disabled).",
                risk = RiskLevel.CAUTION,
                requiredMode = PrivilegeMode.SHIZUKU,
                reversal = "Each disable is journaled with an undo (pm enable --user 0).",
                confidence = Confidence.MEDIUM,
                priorities = setOf(Priority.PRIVACY, Priority.STORAGE_SPACE),
            )
        }

        // --- Animation scale ------------------------------------------------
        if (input.mode != PrivilegeMode.STANDARD && input.animationScale != null &&
            input.animationScale > 1.0
        ) {
            items += RecItem(
                id = "anim_scale",
                title = "Reduce animation scale to 0.5x",
                problem = "Animation scales are set to ${input.animationScale}x, lengthening every transition.",
                evidence = "Developer Options documentation; effect is perceptual (shorter animations), " +
                    "not a benchmarked speedup.",
                evidenceUrl = "https://developer.android.com/studio/run/dev-options",
                compatibility = "Global settings; written via shell (Shizuku/root). Reversible.",
                expectedBenefit = "Feels snappier because animations are shorter. No app work is reduced.",
                risk = RiskLevel.SAFE,
                requiredMode = PrivilegeMode.SHIZUKU,
                reversal = "Set scale back to 1.0x (journaled).",
                confidence = Confidence.MEDIUM,
                priorities = setOf(Priority.EVERYDAY_RESPONSIVENESS),
            )
        }

        // --- Thermal honesty -------------------------------------------------
        if (input.thermalStatus >= 3) {
            items += RecItem(
                id = "thermal_throttling",
                title = "Device is thermally throttling",
                problem = "PowerManager reports severe thermal status; the CPU/GPU will reduce clocks.",
                evidence = "Android thermal API: apps receive thermal status to adapt their own workload; " +
                    "throttling is a safety mechanism.",
                evidenceUrl = "https://developer.android.com/games/optimize/adpf/thermal",
                compatibility = "All devices with API 29+. EIS never disables throttling.",
                expectedBenefit = "Letting the device cool restores normal clocks. Cooling advice only.",
                risk = RiskLevel.SAFE,
                requiredMode = PrivilegeMode.STANDARD,
                reversal = "N/A - no change made.",
                confidence = Confidence.HIGH,
                priorities = setOf(Priority.GAMING_STABILITY, Priority.EVERYDAY_RESPONSIVENESS),
            )
        }

        // --- Gaming ----------------------------------------------------------
        if (Priority.GAMING_STABILITY in input.priorities) {
            if (input.mode == PrivilegeMode.STANDARD) {
                // Standard mode: guidance only - never require privilege we don't have.
                items += RecItem(
                    id = "gaming_guidance",
                    title = "Gaming stability guidance (standard mode)",
                    problem = "High refresh rates raise panel/GPU power draw and heat, which can trigger throttling " +
                        "mid-session.",
                    evidence = "AOSP multiple-refresh-rate policy: peak_refresh_rate constrains display policy; " +
                        "game FPS depends on the game, not the panel rate.",
                    evidenceUrl = "https://source.android.com/docs/core/graphics/multiple-refresh-rate",
                    compatibility = "Standard mode: EIS cannot change display policy. Use the game's own frame " +
                        "limit or Android's built-in Game Mode where the build provides it.",
                    expectedBenefit = "Guidance only; no system change. In-game frame limits reduce GPU load " +
                        "when the game offers them.",
                    risk = RiskLevel.SAFE,
                    requiredMode = PrivilegeMode.STANDARD,
                    reversal = "N/A - no change made.",
                    confidence = Confidence.MEDIUM,
                    priorities = setOf(Priority.GAMING_STABILITY, Priority.BATTERY_LIFE),
                )
            } else {
                items += RecItem(
                    id = "gaming_profile",
                    title = "Use the Maximum Stability gaming profile",
                    problem = "High refresh rates raise panel/GPU power draw and heat, which can trigger throttling " +
                        "mid-session.",
                    evidence = "AOSP multiple-refresh-rate policy: peak_refresh_rate constrains display policy; " +
                        "game FPS depends on the game, not the panel rate.",
                    evidenceUrl = "https://source.android.com/docs/core/graphics/multiple-refresh-rate",
                    compatibility = "Applies peak_refresh_rate via shell; OEM builds may ignore it " +
                        "(EIS verifies read-back).",
                    expectedBenefit = "More thermal headroom at 60 Hz cap; does not change game rendering FPS " +
                        "unless the game itself is frame-limited.",
                    risk = RiskLevel.CAUTION,
                    requiredMode = PrivilegeMode.SHIZUKU,
                    reversal = "Delete peak_refresh_rate setting (journaled).",
                    confidence = Confidence.MEDIUM,
                    priorities = setOf(Priority.GAMING_STABILITY, Priority.BATTERY_LIFE),
                )
            }
        }

        // --- Privacy on known OEM telemetry ------------------------------------
        if (Priority.PRIVACY in input.priorities && input.mode != PrivilegeMode.STANDARD) {
            val telemetry = input.installedPackages.intersect(KNOWN_TELEMETRY)
            if (telemetry.isNotEmpty()) {
                items += RecItem(
                    id = "telemetry_disable",
                    title = "Disable detected telemetry packages",
                    problem = "Detected: ${telemetry.sorted().joinToString(", ")}.",
                    evidence = "Community-maintained lists mark these analytics packages safe to disable " +
                        "(privacy-positive).",
                    evidenceUrl = "https://gist.github.com/mcxiaoke/ade05718f590bcd574b807c4706a00b1",
                    compatibility = "Present on this device; reversible per-package disable.",
                    expectedBenefit = "Stops known analytics endpoints from these packages. " +
                        "No performance guarantee.",
                    risk = RiskLevel.SAFE,
                    requiredMode = PrivilegeMode.SHIZUKU,
                    reversal = "pm enable --user 0 <pkg> for each (journaled).",
                    confidence = Confidence.MEDIUM,
                    priorities = setOf(Priority.PRIVACY),
                )
            }
        }

        // --- Advanced control -------------------------------------------------
        if (Priority.ADVANCED_SYSTEM_CONTROL in input.priorities) {
            if (input.mode == PrivilegeMode.STANDARD) {
                // Standard mode: guidance only - never require privilege we don't have.
                items += RecItem(
                    id = "advanced_control_guidance",
                    title = "Advanced system control requires root (guidance)",
                    problem = "CPU/GPU governor, zRAM and I/O scheduler defaults are OEM-tuned; changing them " +
                        "blindly risks instability.",
                    evidence = "AOSP/kernel documentation for cpufreq devfreq and zRAM; EIS read-first policy.",
                    evidenceUrl = "https://source.android.com/docs/core/power",
                    compatibility = "Standard mode: EIS cannot read or change kernel tuning nodes. Root Lab " +
                        "needs root (Magisk/KernelSU/APatch). Review the OEM's own performance settings instead.",
                    expectedBenefit = "Guidance only; no system change. Avoids blind tuning that risks instability.",
                    risk = RiskLevel.SAFE,
                    requiredMode = PrivilegeMode.STANDARD,
                    reversal = "N/A - no change made.",
                    confidence = Confidence.MEDIUM,
                    priorities = setOf(Priority.ADVANCED_SYSTEM_CONTROL),
                )
            } else {
                items += RecItem(
                    id = "root_lab_inspect",
                    title = "Inspect kernel state before changing anything",
                    problem = "CPU/GPU governor, zRAM and I/O scheduler defaults are OEM-tuned; changing them " +
                        "blindly risks instability.",
                    evidence = "AOSP/kernel documentation for cpufreq devfreq and zRAM; EIS read-first policy.",
                    evidenceUrl = "https://source.android.com/docs/core/power",
                    compatibility = if (input.mode == PrivilegeMode.ROOT)
                        "Root detected - read-only inspection available now."
                    else "Root Lab requires root (Magisk/KernelSU/APatch). Currently ${input.mode.name}.",
                    expectedBenefit = "Informed decisions; no change applied by inspection.",
                    risk = RiskLevel.SAFE,
                    requiredMode = PrivilegeMode.ROOT,
                    reversal = "N/A - read-only.",
                    confidence = Confidence.HIGH,
                    priorities = setOf(Priority.ADVANCED_SYSTEM_CONTROL),
                )
            }
        }

        // --- Low-memory devices -------------------------------------------------
        if (input.profile.totalRamBytes in 1..3_221_225_472L) {
            items += RecItem(
                id = "low_ram",
                title = "Low-RAM device guidance",
                problem = "Device has ${input.profile.totalRamBytes / (1024 * 1024)} MB RAM; background apps " +
                    "will be killed aggressively by Android.",
                evidence = "Android memory management: LMKD kills background processes under pressure; " +
                    "task killers do not improve this.",
                evidenceUrl = "https://developer.android.com/topic/performance/memory-overview",
                compatibility = "All modes. EIS deliberately ships no task-killer.",
                expectedBenefit = "Avoids harmful 'RAM cleaner' patterns; reduces jank source confusion.",
                risk = RiskLevel.SAFE,
                requiredMode = PrivilegeMode.STANDARD,
                reversal = "N/A",
                confidence = Confidence.HIGH,
                priorities = setOf(Priority.EVERYDAY_RESPONSIVENESS, Priority.BATTERY_LIFE),
            )
        }

        // Filter to user priorities when any are chosen; always keep safety items.
        val safetyIds = setOf("thermal_throttling", "storage_low")
        val filtered = items.filter {
            it.priorities.any { p -> p in input.priorities } || it.id in safetyIds
        }
        return filtered.distinctBy { it.id }
    }

    private val KNOWN_TELEMETRY = setOf(
        "com.miui.analytics", "com.miui.msa.global", "com.facebook.appmanager",
        "com.facebook.services", "com.facebook.system",
    )

    private val KNOWN_BLOAT = KNOWN_TELEMETRY + setOf(
        "com.samsung.android.bixby.agent", "com.samsung.android.visionintelligence",
        "com.coloros.gamespace", "com.motorola.gamon", "com.transsion.phonemaster",
        "com.miui.hybrid", "com.google.android.feedback",
    )

    fun skinLabel(skin: OemSkin): String = when (skin) {
        OemSkin.SAMSUNG_ONE_UI -> "Samsung One UI"
        OemSkin.XIAOMI_HYPEROS_OR_MIUI -> "Xiaomi HyperOS/MIUI"
        OemSkin.TECNO_HIOS -> "Tecno HiOS"
        OemSkin.INFINEIX_XOS -> "Infinix XOS"
        OemSkin.OPPO_COLOROS -> "OPPO ColorOS"
        OemSkin.REALME_UI -> "realme UI"
        OemSkin.VIVO_FUNTOUCH -> "vivo Funtouch OS"
        OemSkin.MOTOROLA -> "Motorola"
        OemSkin.GOOGLE_PIXEL -> "Google Pixel"
        OemSkin.GENERIC_AOSP -> "Generic AOSP"
    }
}
