package com.eis.silentoptimizer.debloat

import com.eis.silentoptimizer.core.RiskLevel
import com.eis.silentoptimizer.inventory.InstalledPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebloatDatabaseTest {

    private fun pkg(name: String, system: Boolean, enabled: Boolean = true) = InstalledPackage(
        packageName = name,
        label = name,
        versionName = null,
        isSystem = system,
        isEnabled = enabled,
        isStopped = false,
        uid = 10001,
        apkSizeBytes = null,
        dataSizeBytes = null,
        sourceDir = "/data/app/$name/base.apk",
        category = null,
        installerPackageName = null,
        permissions = emptyList(),
        enabledState = if (enabled) 0 else 2, // DEFAULT / DISABLED_USER
    )

    private fun engine() = DebloatEngine(runnerProvider = { null }, journal = com.eis.silentoptimizer.core.InMemoryJournal())

    // --- Critical block list (spec 5: never auto-disable) --------------------

    @Test
    fun criticalPackagesAlwaysBlocked() {
        val critical = listOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.providers.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.providers.telephony",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.vending",
            "com.google.android.gms",
            "android",
            "com.miui.home",
            "com.sec.android.app.launcher",
            "com.oppo.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.networkstack",
            "com.android.inputdevices",
            "com.android.shell",
            "com.xiaomi.finddevice",
            "com.android.devicepolicy",
            "org.codeaurora.ims",
            "com.mediatek.ims",
        )
        for (c in critical) {
            assertEquals(
                "$c must be BLOCKED",
                Recommendation.BLOCKED,
                DebloatDatabase.classify(c, isSystem = true),
            )
        }
    }

    @Test
    fun blockedClassificationEvenIfProfileExists() {
        // Even if someone adds a profile for a critical package, classify() wins.
        assertEquals(
            Recommendation.BLOCKED,
            DebloatDatabase.classify("com.android.systemui", isSystem = true),
        )
    }

    // --- Evidence-backed profiles ---------------------------------------------

    @Test
    fun knownBloatClassifiedAsExpected() {
        assertEquals(Recommendation.RECOMMENDED, DebloatDatabase.classify("com.miui.analytics", true))
        assertEquals(Recommendation.RECOMMENDED, DebloatDatabase.classify("com.miui.msa.global", true))
        assertEquals(Recommendation.RECOMMENDED, DebloatDatabase.classify("com.facebook.appmanager", true))
        assertEquals(Recommendation.OPTIONAL, DebloatDatabase.classify("com.samsung.android.bixby.agent", true))
        assertEquals(Recommendation.OPTIONAL, DebloatDatabase.classify("com.google.android.feedback", true))
    }

    @Test
    fun highRiskEntriesNeverRecommended() {
        for (entry in DebloatDatabase.ALL) {
            if (entry.risk == RiskLevel.HIGH_RISK) {
                assertTrue(
                    "${entry.packageName} must not be RECOMMENDED",
                    entry.recommendation != Recommendation.RECOMMENDED,
                )
            }
        }
    }

    @Test
    fun unknownSystemPackageIsUnverifiedNotRecommended() {
        assertEquals(Recommendation.NONE, DebloatDatabase.classify("com.vendor.total.mystery", true))
        val classified = engine().classify(listOf(pkg("com.vendor.total.mystery", system = true)))
        assertEquals(Recommendation.NONE, classified.single().category)
        assertTrue(classified.single().reason.contains("evidence"))
        assertFalse(classified.single().recommendedAction.contains("disable"))
    }

    @Test
    fun userInstalledAppsLeftAlone() {
        val classified = engine().classify(listOf(pkg("com.whatsapp", system = false)))
        val c = classified.single()
        assertEquals(Recommendation.NONE, c.category)
        assertTrue(c.recommendedAction.contains("user-installed"))
        // User apps must be SAFE (they are managed through Android settings normally)
        assertEquals(RiskLevel.SAFE, c.risk)
    }

    @Test
    fun everyProfileCarriesEvidenceOrIsExplicitlyLocal() {
        for (entry in DebloatDatabase.ALL) {
            if (entry.evidence == Evidence.EIS_ANALYSIS) {
                // Local analysis entries must explain themselves in the reason.
                assertTrue(entry.reason.isNotBlank())
            } else {
                assertTrue(
                    "${entry.packageName} missing source URL",
                    entry.sourceUrl.startsWith("https://"),
                )
            }
            assertTrue(entry.reversal.isNotBlank())
        }
    }

    @Test
    fun oemProfilesCoverRequiredManufacturers() {
        // Spec 5 OEM coverage: Samsung, Xiaomi/Redmi/POCO, Tecno, Infinix, OPPO,
        // realme, vivo, Motorola, Pixel, universal.
        val oems = setOf(
            DebloatDatabase.ALL.map { it.oem },
        ).flatten()
        for (oem in listOf("universal", "samsung", "xiaomi", "tecno", "infinix", "oppo", "realme", "vivo", "motorola", "pixel")) {
            assertTrue("missing OEM profile: $oem", oems.contains(oem))
        }
    }

    // --- Planning (spec 5) ----------------------------------------------------

    @Test
    fun planExcludesBlockedAndInvalid() {
        val items = engine().classify(
            listOf(
                pkg("com.android.systemui", system = true),   // blocked
                pkg("com.miui.analytics", system = true),     // recommended
                pkg("bad name; rm -rf /", system = true),     // invalid
            ),
        )
        val plan = engine().plan(items, "disable")
        assertEquals(1, plan.size)
        assertEquals("com.miui.analytics", plan.single().packageName)
        assertEquals("pm disable-user --user 0 com.miui.analytics", plan.single().command)
        // Every disable carries an undo
        assertEquals("pm enable --user 0 com.miui.analytics", plan.single().undoCommand)
        assertTrue(plan.single().reversible)
    }

    @Test
    fun uninstallPlanRestoresViaInstallExisting() {
        val items = engine().classify(listOf(pkg("com.facebook.appmanager", system = true)))
        val plan = engine().plan(items, "uninstall-user")
        assertEquals("cmd package install-existing --user 0 com.facebook.appmanager", plan.single().undoCommand)
        assertTrue(plan.single().requiresConfirm)
    }

    @Test
    fun restorePlanCoversDisabledPackages() {
        val plan = engine().restorePlan(
            listOf(
                pkg("com.miui.analytics", system = true, enabled = false),
                pkg("com.android.systemui", system = true, enabled = false), // critical but still restorable
                pkg("com.whatsapp", system = false, enabled = true),          // enabled: not in plan
            ),
        )
        val targets = plan.map { it.packageName }
        assertTrue(targets.contains("com.miui.analytics"))
        assertTrue(targets.contains("com.android.systemui"))
        assertFalse(targets.contains("com.whatsapp"))
        for (p in plan) {
            assertEquals("enable", p.action)
            assertTrue(p.command.startsWith("pm enable --user 0 "))
        }
    }

    @Test
    fun reportContainsEvidenceColumns() {
        val items = engine().classify(
            listOf(pkg("com.miui.analytics", true), pkg("com.android.systemui", true)),
        )
        val report = engine().report(items)
        assertTrue(report.contains("Empire in Silence - Debloat Report"))
        assertTrue(report.contains("com.miui.analytics|RECOMMENDED"))
        assertTrue(report.contains("com.android.systemui|BLOCKED"))
        assertNull(report.substringAfter("package|category", "").indexOf("sensitive").takeIf { it >= 0 })
    }
}
