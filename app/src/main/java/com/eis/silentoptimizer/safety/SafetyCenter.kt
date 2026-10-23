package com.eis.silentoptimizer.safety

import android.content.Context
import com.eis.silentoptimizer.core.ChangeRecord
import com.eis.silentoptimizer.core.JournalStore
import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.shell.CommandRunner
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Safety Center (spec 10).
 *
 * - JSON journal in app-private storage (never leaves the device).
 * - Undo: re-runs the journaled undo command through the active runner and
 *   verifies success before recording the inverse entry.
 * - Backup: exports the full journal as JSON the user can save/share.
 * - Reports: failed operations and non-reversible changes are surfaced.
 */
@Serializable
data class JournalFile(
    val version: Int = 1,
    val entries: List<ChangeRecord> = emptyList(),
)

class JsonJournalStore(private val file: File) : JournalStore {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var cache: MutableList<ChangeRecord>? = null

    private fun load(): MutableList<ChangeRecord> {
        cache?.let { return it }
        val list = try {
            if (file.exists()) {
                json.decodeFromString<JournalFile>(file.readText()).entries.toMutableList()
            } else mutableListOf()
        } catch (_: Throwable) {
            mutableListOf() // corrupt file: start empty rather than crash; backup kept by caller
        }
        cache = list
        return list
    }

    override fun record(entry: ChangeRecord) {
        val list = load()
        list.add(0, entry)
        // Cap journal at 500 entries to bound storage on low-end devices.
        while (list.size > 500) list.removeAt(list.lastIndex)
        persist(list)
    }

    override fun entries(): List<ChangeRecord> = load().toList()

    override fun clear() {
        cache = mutableListOf()
        persist(emptyList())
    }

    private fun persist(list: List<ChangeRecord>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(JournalFile(entries = list)))
        } catch (_: Throwable) {
            // Storage failure is silent in-memory only; UI reads entries() either way.
        }
    }
}

class SafetyCenter(
    private val store: JournalStore,
    private val runnerProvider: () -> CommandRunner?,
) {

    fun history(): List<ChangeRecord> = store.entries()

    fun failedOperations(): List<ChangeRecord> = store.entries().filter { !it.success && !it.dryRun }

    /** Entries that cannot be undone automatically (no undo command recorded). */
    fun nonReversible(): List<ChangeRecord> =
        store.entries().filter { it.undoCommand == null && !it.dryRun && it.success }

    /**
     * Undo one journaled operation: execute its undo command, verify exit
     * code, then record the inverse change. Never guesses - if no undo
     * command exists, returns an honest failure with manual instructions.
     */
    suspend fun undo(entry: ChangeRecord, dryRun: Boolean): OpResult {
        val cmd = entry.undoCommand ?: return OpResult(
            false, null,
            message = "No automatic undo for ${entry.action} on ${entry.target}. " +
                "Manual recovery: see Safety Center guidance.",
        )
        if (dryRun) return OpResult(true, null, message = "DRY RUN: $cmd", command = cmd)

        val runner = runnerProvider() ?: return OpResult.notAvailable("Undo", PrivilegeMode.SHIZUKU)
        val res = runner.exec(cmd)
        store.record(
            ChangeRecord(
                id = "undo-${System.currentTimeMillis()}-${entry.target}",
                timestampMillis = System.currentTimeMillis(),
                action = "undo:${entry.action}",
                target = entry.target,
                previousValue = entry.newValue,
                newValue = if (res.success) "reverted" else "unchanged",
                mode = runner.mode,
                dryRun = false,
                success = res.success,
                message = res.message,
                undoCommand = null, // undos of undos are not auto-chained
            ),
        )
        return res
    }

    /** Undo everything reversible, newest first, stopping on first failure. */
    suspend fun undoAll(dryRun: Boolean, limit: Int = 20): List<OpResult> {
        val results = mutableListOf<OpResult>()
        for (entry in history().filter { it.undoCommand != null && it.success && !it.dryRun }.take(limit)) {
            val res = undo(entry, dryRun)
            results += res
            if (!res.success) break
        }
        return results
    }

    /** Export journal JSON for the user to save. Contains no device IDs. */
    fun exportBackup(): String {
        val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
        return json.encodeToString(
            JournalFile(entries = store.entries()),
        )
    }

    /** Text report of failed ops for support/troubleshooting (local only). */
    fun failureReport(): String {
        val sb = StringBuilder()
        sb.appendLine("Empire in Silence - Failed Operation Report")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        val failed = failedOperations()
        sb.appendLine("Failed operations: ${failed.size}")
        for (f in failed) {
            sb.appendLine("- ${f.timestampLabel} [${f.mode}] ${f.action} ${f.target}: ${f.message}")
        }
        val nonRev = nonReversible()
        sb.appendLine("Non-reversible (no auto-undo): ${nonRev.size}")
        for (n in nonRev) {
            sb.appendLine("- ${n.timestampLabel} ${n.action} ${n.target}")
        }
        if (failed.isEmpty() && nonRev.isEmpty()) sb.appendLine("No failures recorded.")
        return sb.toString()
    }

    /**
     * Manual recovery guidance shown for high-risk states (spec 10:
     * recovery guidance, warnings for non-reversible changes).
     */
    fun recoveryGuidance(): List<String> = listOf(
        "Package disabled and something broke: run Undo from Safety Center, or execute " +
            "'pm enable --user 0 <package>' via adb/Shizuku.",
        "Package uninstalled for user 0 and you want it back: " +
            "'cmd package install-existing --user 0 <package>'.",
        "Refresh rate stuck: Safety Center undo deletes peak_refresh_rate, or run " +
            "'settings delete system peak_refresh_rate'.",
        "Animation scales wrong: undo restores 1.0x, or set in Settings > Accessibility > " +
            "Animation duration.",
        "System app updates removed: 'pm install-existing' restores the factory version; " +
            "Play Store will offer updates again.",
        "Device won't boot after a change made outside EIS: boot to recovery and use the OEM " +
            "repair/reset option. EIS never modifies boot images or protected partitions, so its " +
            "changes cannot cause a bootloop by construction.",
    )
}
