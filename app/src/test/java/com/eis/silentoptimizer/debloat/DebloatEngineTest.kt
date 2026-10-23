package com.eis.silentoptimizer.debloat

import com.eis.silentoptimizer.core.InMemoryJournal
import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.core.RiskLevel
import com.eis.silentoptimizer.inventory.InstalledPackage
import com.eis.silentoptimizer.safety.SafetyCenter
import com.eis.silentoptimizer.shell.CommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rollback logic tests (spec 10 + 13): dry-run purity, journal recording,
 * batch guard, honest failure reporting, and undo execution.
 */
class DebloatEngineTest {

    /** Fake runner: canned success, records every executed command. */
    private class FakeRunner(
        override val mode: PrivilegeMode = PrivilegeMode.SHIZUKU,
        private val succeed: (String) -> Boolean = { true },
    ) : CommandRunner {
        val executed = mutableListOf<String>()
        override suspend fun exec(command: String): OpResult {
            executed += command
            val ok = succeed(command)
            return OpResult(
                success = ok,
                exitCode = if (ok) 0 else 255,
                stdout = if (ok) "Success" else "",
                stderr = if (ok) "" else "Failure",
                message = if (ok) "OK" else "rejected by Android",
                command = command,
            )
        }
    }

    private fun pkg(name: String, system: Boolean = true) = InstalledPackage(
        packageName = name, label = name, versionName = null,
        isSystem = system, isEnabled = true, isStopped = false, uid = 10001,
        apkSizeBytes = null, dataSizeBytes = null, sourceDir = "/system/app/x.apk",
        category = null, installerPackageName = null, permissions = emptyList(),
        enabledState = 0,
    )

    @Test
    fun dryRunExecutesNothingButReturnsPreview() = runBlocking {
        val runner = FakeRunner()
        val journal = InMemoryJournal()
        val engine = DebloatEngine({ runner }, journal)

        val items = engine.classify(listOf(pkg("com.miui.analytics")))
        val plan = engine.plan(items, "disable")
        val results = engine.execute(plan, dryRun = true)

        assertTrue(runner.executed.isEmpty())
        assertTrue(results.single().message.startsWith("DRY RUN"))
        assertTrue(results.single().command.contains("pm disable-user"))
        // Dry runs must not pollute the change journal
        assertTrue(journal.entries().isEmpty())
    }

    @Test
    fun successfulDisableIsJournalledWithUndo() = runBlocking {
        val runner = FakeRunner()
        val journal = InMemoryJournal()
        val engine = DebloatEngine({ runner }, journal)

        val plan = engine.plan(engine.classify(listOf(pkg("com.miui.analytics"))), "disable")
        val results = engine.execute(plan, dryRun = false, batchToken = "t")

        assertTrue(results.single().success)
        assertEquals(listOf("pm disable-user --user 0 com.miui.analytics"), runner.executed)
        val entry = journal.entries().single()
        assertEquals("disable", entry.action)
        assertEquals("com.miui.analytics", entry.target)
        assertTrue(entry.success)
        assertEquals("pm enable --user 0 com.miui.analytics", entry.undoCommand)
    }

    @Test
    fun failedCommandIsRecordedAsFailedNotSuccess() = runBlocking {
        val runner = FakeRunner(succeed = { false })
        val journal = InMemoryJournal()
        val engine = DebloatEngine({ runner }, journal)

        val plan = engine.plan(engine.classify(listOf(pkg("com.miui.analytics"))), "disable")
        val results = engine.execute(plan, dryRun = false, batchToken = "t")

        assertFalse(results.single().success)
        assertEquals(1, journal.entries().size)
        assertFalse(journal.entries().single().success)
        // Safety Center must surface it
        val safety = SafetyCenter(journal) { runner }
        assertEquals(1, safety.failedOperations().size)
        assertTrue(safety.failureReport().contains("Failed operations: 1"))
    }

    @Test
    fun batchWithoutConfirmationIsRefused() = runBlocking {
        val runner = FakeRunner()
        val journal = InMemoryJournal()
        val engine = DebloatEngine({ runner }, journal)

        val plan = engine.plan(
            engine.classify(listOf(pkg("com.miui.analytics"), pkg("com.facebook.services"))),
            "disable",
        )
        assertEquals(2, plan.size)
        val results = engine.execute(plan, dryRun = false, batchToken = null)

        // No command may run without explicit confirmation
        assertTrue(runner.executed.isEmpty())
        assertTrue(results.all { !it.success })
        assertTrue(results.first().message.contains("requires explicit confirmation"))
    }

    @Test
    fun noRunnerAndNoShizukuFailsHonestly() = runBlocking {
        val journal = InMemoryJournal()
        val engine = DebloatEngine({ null }, journal, shizukuGate = null)

        val plan = engine.plan(engine.classify(listOf(pkg("com.miui.analytics"))), "disable")
        val results = engine.execute(plan, dryRun = false, batchToken = "t")

        assertFalse(results.single().success)
        assertTrue(results.single().message.contains("Shizuku or root"))
        // honest privilege message - never claims success
    }

    @Test
    fun undoThroughSafetyCenterRunsRecordedUndoCommand() = runBlocking {
        val runner = FakeRunner()
        val journal = InMemoryJournal()
        val engine = DebloatEngine({ runner }, journal)
        val safety = SafetyCenter(journal) { runner }

        val plan = engine.plan(engine.classify(listOf(pkg("com.miui.analytics"))), "disable")
        engine.execute(plan, dryRun = false, batchToken = "t")
        runner.executed.clear()

        val entry = journal.entries().single()
        val res = safety.undo(entry, dryRun = false)

        assertTrue(res.success)
        assertEquals(listOf("pm enable --user 0 com.miui.analytics"), runner.executed)
        // Inverse must be journalled, and not auto-undoable again
        val inverse = journal.entries().first()
        assertTrue(inverse.action.startsWith("undo:"))
        assertEquals(null, inverse.undoCommand)
    }

    @Test
    fun undoDryRunExecutesNothing() = runBlocking {
        val runner = FakeRunner()
        val journal = InMemoryJournal()
        val engine = DebloatEngine({ runner }, journal)
        val safety = SafetyCenter(journal) { runner }

        val plan = engine.plan(engine.classify(listOf(pkg("com.miui.analytics"))), "disable")
        engine.execute(plan, dryRun = false, batchToken = "t")
        runner.executed.clear()

        val res = safety.undo(journal.entries().single(), dryRun = true)
        assertTrue(res.success)
        assertTrue(res.message.startsWith("DRY RUN"))
        assertTrue(runner.executed.isEmpty())
    }

    @Test
    fun nonReversibleEntriesSurfacedWithoutUndo() = runBlocking {
        val runner = FakeRunner()
        val journal = InMemoryJournal()
        val engine = DebloatEngine({ runner }, journal)
        val safety = SafetyCenter(journal) { runner }

        // 'enable' with no known previous state has no undo command
        val items = engine.classify(listOf(pkg("com.whatsapp", system = false)))
        val plan = engine.plan(items, "enable")
        engine.execute(plan, dryRun = false, batchToken = "t")

        val entry = journal.entries().single()
        assertNotNull(entry)
        // SafetyCenter.undo must refuse honestly rather than guess
        val res = safety.undo(entry, dryRun = false)
        assertFalse(res.success)
        assertTrue(res.message.contains("No automatic undo"))
        assertEquals(0, safety.undoAll(dryRun = false).size)
    }

    @Test
    fun rootRunnerModeIsRespected() = runBlocking {
        val runner = FakeRunner(mode = PrivilegeMode.ROOT)
        val engine = DebloatEngine({ runner }, InMemoryJournal())
        val plan = engine.plan(engine.classify(listOf(pkg("com.miui.analytics"))), "disable")
        val results = engine.execute(plan, dryRun = false, batchToken = "t")
        assertTrue(results.single().success)
        assertEquals(PrivilegeMode.ROOT, runner.mode)
    }

    @Test
    fun riskLevelOfKnownBloatMatchesDatabase() {
        val items = DebloatEngine({ null }, InMemoryJournal())
            .classify(listOf(pkg("com.miui.analytics"), pkg("com.samsung.android.bixby.agent")))
        val byName = items.associateBy { it.pkg.packageName }
        assertEquals(RiskLevel.SAFE, byName["com.miui.analytics"]?.risk)
        assertEquals(RiskLevel.CAUTION, byName["com.samsung.android.bixby.agent"]?.risk)
        // Caution-level items must require confirmation in the plan
        val plan = DebloatEngine({ null }, InMemoryJournal()).plan(items, "disable")
        assertTrue(plan.first { it.packageName.contains("bixby") }.requiresConfirm)
        assertFalse(plan.first { it.packageName.contains("analytics") }.requiresConfirm)
    }
}
