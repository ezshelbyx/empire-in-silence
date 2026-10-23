package com.eis.silentoptimizer.privilege

import com.eis.silentoptimizer.core.PrivilegeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeDetectorTest {

    private fun state(
        shizukuInstalled: Boolean = false,
        rootManager: RootManager = RootManager.NONE,
        binderAlive: Boolean = false,
        granted: Boolean = false,
        preV11: Boolean = false,
        rootAvailable: Boolean = false,
        rootGranted: Boolean = false,
    ) = PrivilegeDetector.composeState(
        shizukuInstalled = shizukuInstalled,
        rootManager = rootManager,
        shizukuBinderAlive = binderAlive,
        shizukuPermissionGranted = granted,
        shizukuPreV11 = preV11,
        rootAvailable = rootAvailable,
        rootGranted = rootGranted,
    )

    @Test
    fun defaultDeviceIsStandardMode() {
        val s = state()
        assertEquals(PrivilegeMode.STANDARD, s.mode)
        assertFalse(s.canRunShell)
        assertFalse(s.canWriteSystem)
        assertTrue(s.notes.isNotEmpty())
    }

    @Test
    fun shizukuNeedsInstalledRunningAndAuthorized() {
        // installed but not running
        assertEquals(PrivilegeMode.STANDARD, state(shizukuInstalled = true).mode)
        // running but unauthorized
        assertEquals(
            PrivilegeMode.STANDARD,
            state(shizukuInstalled = true, binderAlive = true).mode,
        )
        // authorized -> SHIZUKU
        val ok = state(shizukuInstalled = true, binderAlive = true, granted = true)
        assertEquals(PrivilegeMode.SHIZUKU, ok.mode)
        assertTrue(ok.canRunShell)
        assertFalse(ok.canWriteSystem) // shell identity is not root
        // pre-v11 rejected even with permission
        assertEquals(
            PrivilegeMode.STANDARD,
            state(shizukuInstalled = true, binderAlive = true, granted = true, preV11 = true).mode,
        )
    }

    @Test
    fun rootBeatsShizukuWhenGranted() {
        val s = state(
            shizukuInstalled = true, binderAlive = true, granted = true,
            rootAvailable = true, rootGranted = true, rootManager = RootManager.KERNELSU,
        )
        assertEquals(PrivilegeMode.ROOT, s.mode)
        assertTrue(s.canWriteSystem)
        assertEquals(RootManager.KERNELSU, s.rootManager)
    }

    @Test
    fun rootAvailableButNotGrantedDoesNotUnlockRoot() {
        val s = state(
            rootAvailable = true, rootGranted = false, rootManager = RootManager.MAGISK,
        )
        assertEquals(PrivilegeMode.STANDARD, s.mode)
        assertEquals(RootManager.NONE, s.rootManager) // never claim a manager without grant
        assertTrue(s.notes.any { it.contains("superuser") })
    }

    @Test
    fun shizukuStaysActiveWhileRootUngranted() {
        val s = state(
            shizukuInstalled = true, binderAlive = true, granted = true,
            rootAvailable = true, rootGranted = false, rootManager = RootManager.MAGISK,
        )
        assertEquals(PrivilegeMode.SHIZUKU, s.mode)
    }

    @Test
    fun rebootNotePresentWhenShizukuNotRunning() {
        val s = state(shizukuInstalled = true, binderAlive = false)
        assertTrue(s.notes.any { it.contains("reboot") })
    }

    @Test
    fun apatchManagerValueIsPreservedVerbatim() {
        // APatch support is explicitly "detected but unverified" (spec 4):
        // we only record presence; we do not fabricate a verified mode.
        val s = state(
            rootAvailable = true, rootGranted = true, rootManager = RootManager.APATCH,
        )
        assertEquals(PrivilegeMode.ROOT, s.mode)
        assertEquals(RootManager.APATCH, s.rootManager)
    }

    @Test
    fun rootManagerPackagesMapToDistinctValues() {
        assertEquals(RootManager.MAGISK, PrivilegeDetector.rootManagerForPackage("com.topjohnwu.magisk"))
        assertEquals(RootManager.KERNELSU, PrivilegeDetector.rootManagerForPackage("me.weishu.kernelsu"))
        assertEquals(RootManager.APATCH, PrivilegeDetector.rootManagerForPackage("me.bmax.apatch"))
        assertEquals(RootManager.NONE, PrivilegeDetector.rootManagerForPackage("com.example.app"))
    }
}
