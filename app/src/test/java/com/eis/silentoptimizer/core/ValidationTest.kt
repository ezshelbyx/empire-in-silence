package com.eis.silentoptimizer.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    @Test
    fun validPackageNamesAccepted() {
        assertTrue(Validation.isValidPackageName("com.samsung.android.bixby.agent"))
        assertTrue(Validation.isValidPackageName("com.miui.analytics"))
        assertTrue(Validation.isValidPackageName("com.facebook.appmanager"))
        assertTrue(Validation.isValidPackageName("a.b"))
    }

    @Test
    fun shellInjectionInPackageNameRejected() {
        assertFalse(Validation.isValidPackageName("com.evil; rm -rf /"))
        assertFalse(Validation.isValidPackageName("com.evil && reboot"))
        assertFalse(Validation.isValidPackageName("com.evil`id`"))
        assertFalse(Validation.isValidPackageName("com.evil\$(id)"))
        assertFalse(Validation.isValidPackageName("com.evil > /data/x"))
        assertFalse(Validation.isValidPackageName("com.evil | sh"))
        assertFalse(Validation.isValidPackageName("com.evil\nrm -rf /"))
        assertFalse(Validation.isValidPackageName("../../../system"))
        assertFalse(Validation.isValidPackageName("com.evil*"))
    }

    @Test
    fun malformedPackageNamesRejected() {
        assertFalse(Validation.isValidPackageName(""))
        assertFalse(Validation.isValidPackageName("no"))
        assertFalse(Validation.isValidPackageName("no-dot"))
        assertFalse(Validation.isValidPackageName(".starts.with.dot"))
        assertFalse(Validation.isValidPackageName("ends.with.dot."))
        assertFalse(Validation.isValidPackageName("com..double.dot"))
        // 3 char floor: "a.b" passes but "a." does not
        assertFalse(Validation.isValidPackageName("a."))
    }

    @Test
    fun shellMetacharacterDetection() {
        assertTrue(Validation.containsShellMetacharacters(";"))
        assertTrue(Validation.containsShellMetacharacters("&&"))
        assertTrue(Validation.containsShellMetacharacters("`"))
        assertTrue(Validation.containsShellMetacharacters("$"))
        assertTrue(Validation.containsShellMetacharacters("\n"))
        assertFalse(Validation.containsShellMetacharacters("com.android.phone"))
        assertFalse(Validation.containsShellMetacharacters("0.5"))
    }

    @Test
    fun settingsKeysAndValues() {
        assertTrue(Validation.isValidSettingsKey("peak_refresh_rate"))
        assertTrue(Validation.isValidSettingsKey("window_animation_scale"))
        assertFalse(Validation.isValidSettingsKey("bad key"))          // space
        assertFalse(Validation.isValidSettingsKey("key;rm"))
        assertFalse(Validation.isValidSettingsKey(""))
        assertTrue(Validation.isValidSettingsValue("120"))
        assertTrue(Validation.isValidSettingsValue("0.5"))
        assertFalse(Validation.isValidSettingsValue("120; reboot"))
        assertFalse(Validation.isValidSettingsValue("a\$(b)"))
    }

    @Test
    fun animationScalesWhitelisted() {
        assertTrue(Validation.isValidAnimationScale("0.0"))
        assertTrue(Validation.isValidAnimationScale("0.5"))
        assertTrue(Validation.isValidAnimationScale("1.0"))
        assertFalse(Validation.isValidAnimationScale("0.7"))
        assertFalse(Validation.isValidAnimationScale("-1.0"))
        assertFalse(Validation.isValidAnimationScale("abc"))
    }

    @Test
    fun sysfsPathsRestrictedToAllowlist() {
        assertTrue(Validation.isAllowedSysfsPath("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"))
        assertTrue(Validation.isAllowedSysfsPath("/sys/class/thermal/thermal_zone0/temp"))
        assertTrue(Validation.isAllowedSysfsPath("/sys/block/zram0/disksize"))
        assertTrue(Validation.isAllowedSysfsPath("/proc/meminfo"))
        // traversal and off-allowlist paths rejected
        assertFalse(Validation.isAllowedSysfsPath("/sys/class/thermal/../../etc/passwd"))
        assertFalse(Validation.isAllowedSysfsPath("/data/system/packages.xml"))
        assertFalse(Validation.isAllowedSysfsPath("/system/build.prop"))
        assertFalse(Validation.isAllowedSysfsPath("sys/class/thermal/temp")) // no leading slash
        assertFalse(Validation.isAllowedSysfsPath("/sys/class/thermal/temp; reboot"))
    }

    @Test
    fun refreshRatesConstrained() {
        assertTrue(Validation.isValidRefreshRate(60))
        assertTrue(Validation.isValidRefreshRate(120))
        assertFalse(Validation.isValidRefreshRate(41))
        assertFalse(Validation.isValidRefreshRate(1000))
        assertFalse(Validation.isValidRefreshRate(-60))
    }
}
