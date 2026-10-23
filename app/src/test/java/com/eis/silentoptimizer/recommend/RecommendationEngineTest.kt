package com.eis.silentoptimizer.recommend

import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.core.RiskLevel
import com.eis.silentoptimizer.device.DeviceProfile
import com.eis.silentoptimizer.device.OemSkin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {

    private fun profile(
        ram: Long = 8L * 1024 * 1024 * 1024,
        storageTotal: Long = 128L * 1024 * 1024 * 1024,
        storageFree: Long = 60L * 1024 * 1024 * 1024,
    ) = DeviceProfile(
        manufacturer = "Xiaomi", brand = "Redmi", model = "Note 10S", device = "mojito",
        product = "mojito", hardware = "mt6785", androidVersion = "14", sdkInt = 34,
        securityPatch = "2025-01-01", buildId = "UKQ1.231003", supportedAbis = listOf("arm64-v8a"),
        totalRamBytes = ram, totalStorageBytes = storageTotal, availableStorageBytes = storageFree,
        cpuCores = 8, isEmulator = false, oemSkin = OemSkin.XIAOMI_HYPEROS_OR_MIUI,
    )

    private fun input(
        mode: PrivilegeMode = PrivilegeMode.STANDARD,
        priorities: Set<Priority> = Priority.entries.toSet(),
        packages: Set<String> = emptySet(),
        free: Long = 60L * 1024 * 1024 * 1024,
        total: Long = 128L * 1024 * 1024 * 1024,
        thermal: Int = 0,
        ram: Long = 8L * 1024 * 1024 * 1024,
    ) = RecInput(
        profile = profile(ram, total, free),
        mode = mode,
        priorities = priorities,
        installedPackages = packages,
        availableStorageBytes = free,
        totalStorageBytes = total,
        thermalStatus = thermal,
        animationScaleKnown = false,
        animationScale = null,
    )

    @Test
    fun standardModeNeverRecommendsPrivilegedActions() {
        val items = RecommendationEngine.evaluate(input(mode = PrivilegeMode.STANDARD))
        for (item in items) {
            assertTrue(
                "${item.id} requires privilege in Standard mode",
                item.requiredMode == PrivilegeMode.STANDARD,
            )
        }
    }

    @Test
    fun noNumericalPerformanceClaimsAnywhere() {
        val items = RecommendationEngine.evaluate(input(mode = PrivilegeMode.ROOT))
        val fabricatedFps = Regex(".*\\d+\\s*fps.*", RegexOption.IGNORE_CASE)
        val percentGain = Regex(".*\\d+% (faster|improvement|more fps).*", RegexOption.IGNORE_CASE)
        for (item in items) {
            val benefit = item.expectedBenefit.lowercase()
            assertFalse("${item.id}: '${item.expectedBenefit}'", benefit.contains("faster by"))
            assertFalse("${item.id}: fabricated fps claim", fabricatedFps.matches(item.expectedBenefit))
            assertFalse("${item.id}: fabricated percentage gain", percentGain.matches(item.expectedBenefit))
        }
    }

    @Test
    fun thermalThrottleAdviceAlwaysSurfacesEvenWithoutPriority() {
        val items = RecommendationEngine.evaluate(
            input(priorities = setOf(Priority.PRIVACY), thermal = 3),
        )
        assertTrue(items.any { it.id == "thermal_throttling" })
        // Safety items must never suggest disabling protection
        val t = items.first { it.id == "thermal_throttling" }
        assertTrue(t.compatibility.contains("never disables"))
        assertTrue(t.requiredMode == PrivilegeMode.STANDARD)
    }

    @Test
    fun lowStorageSurfacesWithHighConfidence() {
        val items = RecommendationEngine.evaluate(
            input(free = 4L * 1024 * 1024 * 1024, total = 128L * 1024 * 1024 * 1024),
        )
        val low = items.firstOrNull { it.id == "storage_low" }
        assertTrue(low != null)
        assertTrue(low!!.confidence == Confidence.HIGH)
        assertTrue(low.risk == RiskLevel.SAFE)
        assertTrue(low.reversal.isNotBlank())
    }

    @Test
    fun telemetryRecommendationOnlyWhenPackagesPresentWithPrivilege() {
        val packages = setOf("com.miui.analytics", "com.miui.msa.global")
        // Privileged + privacy priority + present
        val withPriv = RecommendationEngine.evaluate(
            input(mode = PrivilegeMode.SHIZUKU, priorities = setOf(Priority.PRIVACY), packages = packages),
        )
        assertTrue(withPriv.any { it.id == "telemetry_disable" })

        // Not present -> no recommendation
        val absent = RecommendationEngine.evaluate(
            input(mode = PrivilegeMode.SHIZUKU, priorities = setOf(Priority.PRIVACY), packages = emptySet()),
        )
        assertFalse(absent.any { it.id == "telemetry_disable" })
    }

    @Test
    fun gamingPriorityRequiresPrivilegeOrExplainsFallback() {
        val standard = RecommendationEngine.evaluate(
            input(mode = PrivilegeMode.STANDARD, priorities = setOf(Priority.GAMING_STABILITY)),
        )
        // In standard mode gaming items must not require Shizuku (they'd be unusable)
        for (item in standard.filter { Priority.GAMING_STABILITY in it.priorities }) {
            assertTrue(item.requiredMode == PrivilegeMode.STANDARD)
        }

        val shizuku = RecommendationEngine.evaluate(
            input(mode = PrivilegeMode.SHIZUKU, priorities = setOf(Priority.GAMING_STABILITY)),
        )
        assertTrue(shizuku.any { it.id == "gaming_profile" })
        // Gaming profile recommendation must honestly warn it doesn't control game FPS
        val g = shizuku.first { it.id == "gaming_profile" }
        assertTrue(g.expectedBenefit.contains("FPS"))
        assertTrue(g.requiredMode == PrivilegeMode.SHIZUKU)
    }

    @Test
    fun lowRamGuidanceForSmallDevices() {
        val items = RecommendationEngine.evaluate(
            input(ram = 2L * 1024 * 1024 * 1024, priorities = setOf(Priority.EVERYDAY_RESPONSIVENESS)),
        )
        assertTrue(items.any { it.id == "low_ram" })
        // Never recommends task killers
        assertFalse(items.any { it.expectedBenefit.contains("task killer") })
    }

    @Test
    fun everyRecommendationHasFullEvidenceFields() {
        val items = RecommendationEngine.evaluate(input(mode = PrivilegeMode.ROOT))
        assertTrue(items.isNotEmpty())
        for (item in items) {
            assertTrue("${item.id} problem", item.problem.isNotBlank())
            assertTrue("${item.id} evidence", item.evidence.isNotBlank())
            assertTrue("${item.id} url", item.evidenceUrl.startsWith("https://"))
            assertTrue("${item.id} compat", item.compatibility.isNotBlank())
            assertTrue("${item.id} risk", item.risk != RiskLevel.HIGH_RISK) // never recommend high-risk
            assertTrue("${item.id} reversal", item.reversal.isNotBlank())
            assertTrue("${item.id} confidence", item.confidence in Confidence.entries)
        }
    }

    @Test
    fun priorityFilteringRespectsUserSelection() {
        val batteryOnly = RecommendationEngine.evaluate(
            input(priorities = setOf(Priority.BATTERY_LIFE)),
        )
        // Only battery-related or safety items allowed through
        for (item in batteryOnly) {
            assertTrue(
                "${item.id} leaked through priority filter",
                Priority.BATTERY_LIFE in item.priorities || item.id in setOf("thermal_throttling", "storage_low"),
            )
        }
    }
}
