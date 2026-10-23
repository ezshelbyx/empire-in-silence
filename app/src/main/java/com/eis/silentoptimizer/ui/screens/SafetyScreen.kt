package com.eis.silentoptimizer.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eis.silentoptimizer.core.ChangeRecord
import com.eis.silentoptimizer.ui.DimLabel
import com.eis.silentoptimizer.ui.Divider
import com.eis.silentoptimizer.ui.EisCard
import com.eis.silentoptimizer.ui.EisViewModel
import com.eis.silentoptimizer.ui.KeyValueRow
import com.eis.silentoptimizer.ui.ScreenHeader
import com.eis.silentoptimizer.ui.SectionTitle
import com.eis.silentoptimizer.ui.theme.MutedGold
import com.eis.silentoptimizer.ui.theme.RiskCaution
import com.eis.silentoptimizer.ui.theme.RiskHigh
import com.eis.silentoptimizer.ui.theme.SilverDim
import com.eis.silentoptimizer.ui.theme.StatusBad
import com.eis.silentoptimizer.ui.theme.StatusGood

/**
 * Safety Center (spec 10): change history, undo (dry-run capable), export,
 * failure report, recovery guidance. Logs stay local; clipboard export is a
 * user-initiated action, never automatic.
 */
@Composable
fun SafetyScreen(vm: EisViewModel) {
    val state by vm.ui.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                "Safety Center",
                "Plan -> confirm -> execute -> verify -> journal -> undo",
            )
        }

        // Batch undo / export row
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle(
                        "HISTORY",
                        "${state.recentActions.size} recent / full journal below",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                val latest = vm.journalEntries().firstOrNull()
                                if (latest == null) {
                                    message = "Nothing to undo."
                                } else {
                                    vm.undoEntry(latest, dryRun = true) { r ->
                                        message = "DRY RUN: ${r.command} -> ${r.message}"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.weight(1f),
                        ) { Text("Undo latest (dry run)", color = MutedGold, fontSize = 12.sp) }
                        Button(
                            onClick = {
                                val latest = vm.journalEntries().firstOrNull()
                                if (latest == null) {
                                    message = "Nothing to undo."
                                } else {
                                    vm.undoEntry(latest, dryRun = false) { r ->
                                        message = (if (r.success) "Undone: " else "Undo failed: ") + r.message
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.weight(1f),
                        ) { Text("Undo latest", color = RiskCaution, fontSize = 12.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                val backup = vm.exportBackup()
                                copyToClipboard(context, "eis_backup", backup)
                                message = "Backup JSON copied to clipboard (${backup.length} chars). " +
                                    "It contains no device identifiers."
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.weight(1f),
                        ) { Text("Export backup", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp) }
                        Button(
                            onClick = {
                                val report = vm.failureReport()
                                copyToClipboard(context, "eis_report", report)
                                message = "Failure report copied to clipboard."
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.weight(1f),
                        ) { Text("Failure report", color = MutedGold, fontSize = 12.sp) }
                    }
                    message?.let {
                        Text(it, fontSize = 11.sp, color = MutedGold, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // Full journal
        val entries = vm.journalEntries()
        if (entries.isEmpty()) {
            item {
                Text(
                    "No system changes recorded yet.",
                    color = SilverDim, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(entries, key = { it.id }) { entry ->
            JournalRow(entry, vm) { message = it }
        }

        // Component report
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("COMPONENT STATUS")
                    vm.componentReport().forEach { (k, v) -> KeyValueRow(k, v) }
                }
            }
        }

        // Recovery guidance
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("RECOVERY GUIDANCE")
                    vm.recoveryGuidance().forEach { g ->
                        Text("- $g", style = MaterialTheme.typography.bodySmall,
                            color = SilverDim, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }

        // Non-reversible warning zone
        val nonRev = entries.filter { it.undoCommand == null && !it.dryRun && it.success }
        if (nonRev.isNotEmpty()) {
            item {
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle("NON-REVERSIBLE CHANGES", "${nonRev.size} recorded")
                        Text(
                            "These changes have no automatic undo. Manual instructions are listed in " +
                                "each entry; factory reset always restores system packages.",
                            color = RiskHigh, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("PRIVACY")
                    Text(
                        "Logs and journal are stored in app-private storage only. Nothing is uploaded: " +
                            "EIS holds no INTERNET permission. Device identifiers, package lists and " +
                            "system logs are never transmitted. See docs/SECURITY_AND_PRIVACY.md.",
                        color = SilverDim, style = MaterialTheme.typography.bodySmall,
                    )
                    Divider()
                    DimLabel("MOTTO: SILENT AUTHORITY. MAXIMUM CONTROL.")
                }
            }
        }
    }
}

@Composable
private fun JournalRow(entry: ChangeRecord, vm: EisViewModel, onMessage: (String) -> Unit) {
    EisCard {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    (if (entry.success) "OK" else "FAIL") + (if (entry.dryRun) " / DRY" else ""),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = if (entry.success) StatusGood else StatusBad,
                )
                Text(entry.mode.name, fontSize = 10.sp, color = SilverDim)
            }
            Text("${entry.action} -> ${entry.target}", style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium)
            Text(entry.timestampLabel, fontSize = 10.sp, color = SilverDim)
            entry.previousValue?.let { KeyValueRow("Previous", it) }
            entry.newValue?.let { KeyValueRow("New", it) }
            Text(entry.message, fontSize = 11.sp, color = SilverDim)
            if (!entry.dryRun && entry.undoCommand != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            vm.undoEntry(entry, dryRun = true) { r -> onMessage("DRY RUN: ${r.command} ${r.message}") }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text("Undo (dry)", color = MutedGold, fontSize = 11.sp) }
                    Button(
                        onClick = {
                            vm.undoEntry(entry, dryRun = false) { r ->
                                onMessage((if (r.success) "Undone: " else "Failed: ") + r.message)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text("Undo", color = RiskCaution, fontSize = 11.sp) }
                }
                Text("undo: ${entry.undoCommand}", fontSize = 10.sp, color = SilverDim)
            } else if (!entry.dryRun && entry.undoCommand == null && entry.success) {
                Text(
                    "No automatic undo - manual recovery only.",
                    fontSize = 10.sp, color = RiskHigh,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    } catch (_: Throwable) {
        // Clipboard unavailable; surfaced by caller message only on success paths.
    }
}
