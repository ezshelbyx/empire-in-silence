package com.eis.silentoptimizer.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandTemplatesTest {

    @Test
    fun disableAndEnableTemplates() {
        assertEquals(
            "pm disable-user --user 0 com.miui.analytics",
            CommandTemplates.disableUser("com.miui.analytics"),
        )
        assertEquals(
            "pm enable --user 0 com.miui.analytics",
            CommandTemplates.enableUser("com.miui.analytics"),
        )
        assertNull(CommandTemplates.disableUser("com.evil; rm -rf /"))
        assertNull(CommandTemplates.enableUser("$(reboot)"))
    }

    @Test
    fun restoreTemplates() {
        assertEquals(
            "cmd package install-existing --user 0 com.samsung.android.bixby.agent",
            CommandTemplates.installExisting("com.samsung.android.bixby.agent"),
        )
        assertEquals(
            "pm uninstall --user 0 com.facebook.appmanager",
            CommandTemplates.uninstallUser("com.facebook.appmanager"),
        )
        assertNull(CommandTemplates.installExisting("a.b && reboot"))
    }

    @Test
    fun settingsTemplatesValidateNamespace() {
        assertNotNull(CommandTemplates.settingsPut("system", "peak_refresh_rate", "120"))
        assertNull(CommandTemplates.settingsPut("rm", "peak_refresh_rate", "120"))
        assertNull(CommandTemplates.settingsPut("system", "peak;reboot", "120"))
        assertNull(CommandTemplates.settingsPut("system", "peak_refresh_rate", "120; reboot"))
        assertEquals(
            "settings get global window_animation_scale",
            CommandTemplates.settingsGet("global", "window_animation_scale"),
        )
    }

    @Test
    fun animationTemplateOnlyAllowsKnownScales() {
        assertEquals(
            "settings put global window_animation_scale 0.5",
            CommandTemplates.animationScale("window", 0.5f),
        )
        assertNotNull(CommandTemplates.animationScale("animator", 0f))
        assertNull(CommandTemplates.animationScale("bogus", 0.5f))
        assertNull(CommandTemplates.animationScale("window", 0.7f))
    }

    @Test
    fun refreshRateTemplateValidated() {
        assertEquals(
            "settings put system peak_refresh_rate 120",
            CommandTemplates.setPeakRefreshRate(120),
        )
        assertNull(CommandTemplates.setPeakRefreshRate(41))
        assertNull(CommandTemplates.setPeakRefreshRate(1000))
        assertEquals("settings delete system peak_refresh_rate", CommandTemplates.clearPeakRefreshRate())
    }

    @Test
    fun sysfsWriteTemplateRejectsUnsafeTargets() {
        // Allowed: single token to allowlisted sysfs node
        assertNotNull(
            CommandTemplates.writeNode(
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
                "schedutil",
            ),
        )
        // Value injection rejected
        assertNull(
            CommandTemplates.writeNode(
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
                "schedutil; reboot",
            ),
        )
        assertNull(
            CommandTemplates.writeNode(
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
                "a\$(id)",
            ),
        )
        // Path traversal rejected
        assertNull(CommandTemplates.writeNode("/sys/class/thermal/../../etc/x", "1"))
        // procfs critical paths rejected even inside read allowlist
        assertNull(CommandTemplates.writeNode("/proc/sys/kernel/panic", "0"))
        assertNull(CommandTemplates.writeNode("/proc/sys/kernel/sysrq", "1"))
        // Off-allowlist paths rejected
        assertNull(CommandTemplates.writeNode("/data/system/packages.xml", "x"))
        assertNull(CommandTemplates.writeNode("/system/build.prop", "x"))
    }

    @Test
    fun readTemplatesRestrictPaths() {
        assertNotNull(CommandTemplates.readNode("/proc/meminfo"))
        assertNotNull(CommandTemplates.readNode("/sys/class/thermal/thermal_zone0/temp"))
        assertNull(CommandTemplates.readNode("/data/system/packages.xml"))
        assertNull(CommandTemplates.readNode("/system/build.prop"))
    }

    @Test
    fun allowlistAcceptsGeneratedCommands() {
        val generated = listOfNotNull(
            CommandTemplates.disableUser("com.miui.analytics"),
            CommandTemplates.enableUser("com.miui.analytics"),
            CommandTemplates.uninstallUser("com.facebook.appmanager"),
            CommandTemplates.installExisting("com.facebook.appmanager"),
            CommandTemplates.settingsPut("system", "peak_refresh_rate", "120"),
            CommandTemplates.settingsGet("global", "animator_duration_scale"),
            CommandTemplates.clearPeakRefreshRate(),
            CommandTemplates.readNode("/proc/version"),
            CommandTemplates.writeNode(
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
                "powersave",
            ),
            CommandTemplates.listPackages(systemOnly = true),
            CommandTemplates.listDisabledPackages(),
            CommandTemplates.whoami(),
        )
        for (cmd in generated) {
            assertTrue("should allow: $cmd", CommandTemplates.isAllowedCommand(cmd))
        }
    }

    @Test
    fun allowlistRejectsHostileCommands() {
        val hostile = listOf(
            "rm -rf /data",
            "pm disable-user --user 0 com.a.b; rm -rf /",
            "cat /data/system/packages.xml",
            "reboot",
            "settings put global hidden_api_policy 1 && pm disable com.a.b",
            "sh -c 'id'",
            "am start -n com.evil/.Main",
            "svc power reboot",
            "dd if=/dev/block/sda of=/dev/null",
            "cat /proc/meminfo && reboot",
        )
        for (cmd in hostile) {
            assertFalse("should reject: $cmd", CommandTemplates.isAllowedCommand(cmd))
        }
    }

    @Test
    fun listTemplatesAreReadOnly() {
        assertEquals("pm list packages --user 0", CommandTemplates.listPackages(false))
        assertEquals("pm list packages -s --user 0", CommandTemplates.listPackages(true))
        assertEquals("pm list packages -d --user 0", CommandTemplates.listDisabledPackages())
        for (cmd in listOf(
            CommandTemplates.listPackages(false),
            CommandTemplates.listPackages(true),
            CommandTemplates.listDisabledPackages(),
        )) {
            assertFalse(cmd.contains("disable") && !cmd.contains("packages -d"))
            assertTrue(CommandTemplates.isAllowedCommand(cmd))
        }
    }

    @Test
    fun moduleTemplatesValidateIds() {
        assertNotNull(CommandTemplates.readModuleProp("awesome-module"))
        assertNull(CommandTemplates.readModuleProp("bad id"))
        assertNull(CommandTemplates.readModuleProp("../other/module"))
        assertEquals("ls /data/adb/modules", CommandTemplates.listMagiskModules())
    }
}
