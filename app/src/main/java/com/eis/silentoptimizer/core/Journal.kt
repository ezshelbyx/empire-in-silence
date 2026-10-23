package com.eis.silentoptimizer.core

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only operation journal (spec 10). Persists [ChangeRecord] entries as
 * JSON in app-private storage. Nothing here ever leaves the device.
 */
interface JournalStore {
    fun record(entry: ChangeRecord)
    fun entries(): List<ChangeRecord>
    fun clear()
}

/**
 * One journal entry describing a system change, its previous state, and how
 * to reverse it.
 */
@Serializable
data class ChangeRecord(
    val id: String,
    val timestampMillis: Long,
    val action: String,          // e.g. "disable-user", "settings-put", "sysfs-write"
    val target: String,          // package name / settings key / sysfs path
    val previousValue: String?,  // state before the change, when captured
    val newValue: String?,       // state after the change
    val mode: PrivilegeMode,
    val dryRun: Boolean,
    val success: Boolean,
    val message: String,
    val undoCommand: String?,    // command that reverses this change, if reversible
) {
    val timestampLabel: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(timestampMillis))
}

/** In-memory implementation used by unit tests and as a startup fallback. */
class InMemoryJournal : JournalStore {
    private val items = mutableListOf<ChangeRecord>()
    override fun record(entry: ChangeRecord) { items.add(0, entry) }
    override fun entries(): List<ChangeRecord> = items.toList()
    override fun clear() = items.clear()
}
