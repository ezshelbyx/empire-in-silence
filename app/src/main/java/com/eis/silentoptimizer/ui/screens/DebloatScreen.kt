package com.eis.silentoptimizer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eis.silentoptimizer.debloat.Recommendation
import com.eis.silentoptimizer.ui.DimLabel
import com.eis.silentoptimizer.ui.EisCard
import com.eis.silentoptimizer.ui.EisViewModel
import com.eis.silentoptimizer.ui.RiskBadge
import com.eis.silentoptimizer.ui.ScreenHeader
import com.eis.silentoptimizer.ui.SectionTitle
import com.eis.silentoptimizer.ui.theme.MutedGold
import com.eis.silentoptimizer.ui.theme.RiskHigh
import com.eis.silentoptimizer.ui.theme.SilverDim
import com.eis.silentoptimizer.ui.theme.StatusBad
import com.eis.silentoptimizer.ui.theme.StatusGood

/**
 * Debloat screen: scan, search, filter, select, dry-run, execute, results.
 * Critical packages are never selectable. Batch actions >1 require the
 * explicit "confirm batch" toggle (spec 5: no mass debloating without
 * explicit user confirmation).
 */
@Composable
fun DebloatScreen(vm: EisViewModel) {
    val state by vm.debloat.collectAsState()
    var expanded by remember { mutableStateOf<String?>(null) }
    var batchConfirmed by remember { mutableStateOf(false) }

    val filtered = state.items.filter { item ->
        val q = state.query.trim().lowercase()
        val matchesQuery = q.isEmpty() ||
            item.pkg.packageName.contains(q, true) ||
            item.pkg.label.contains(q, true)
        val matchesCat = state.categoryFilter == null || item.category == state.categoryFilter
        matchesQuery && matchesCat
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(
                "Debloat",
                "Reversible disables first. Critical packages are locked. Evidence per package.",
            )
        }

        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = { vm.setDebloatQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search package or app name") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MutedGold,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val filters = listOf<Pair<String, Recommendation?>>(
                    "All" to null,
                    "Recommended" to Recommendation.RECOMMENDED,
                    "Optional" to Recommendation.OPTIONAL,
                    "Blocked" to Recommendation.BLOCKED,
                    "Unknown" to Recommendation.NONE,
                )
                items(filters) { (label, cat) ->
                    val selected = state.categoryFilter == cat
                    Text(
                        label,
                        fontSize = 12.sp,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else SilverDim,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clickable { vm.setDebloatFilter(cat) }
                            .then(
                                if (selected) Modifier.padding(0.dp) else Modifier,
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.scanning) "Scanning..." else "${filtered.size} shown / ${state.items.size} total",
                    style = MaterialTheme.typography.bodySmall,
                    color = SilverDim,
                )
                TextButtonish("Rescan") { vm.scanDebloat() }
            }
            if (state.scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp),
                    color = MutedGold,
                    strokeWidth = 2.dp,
                )
            }
        }

        // Selection summary + actions
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("SELECTION", "${state.selected.size} package(s) selected")
                    if (state.selected.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = batchConfirmed,
                                onCheckedChange = { batchConfirmed = it },
                                colors = CheckboxDefaults.colors(checkedColor = MutedGold),
                            )
                            Text(
                                "I confirm a batch of ${state.selected.size} changes (applied one at a time)",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { vm.debloatAction("disable", dryRun = true) },
                            enabled = state.selected.isNotEmpty() && !state.busy,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f),
                        ) { Text("Dry run", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp) }
                        Button(
                            onClick = { vm.debloatAction("disable", dryRun = false, allowBatch = batchConfirmed || state.selected.size == 1) },
                            enabled = state.selected.isNotEmpty() && !state.busy &&
                                (state.selected.size == 1 || batchConfirmed),
                            colors = ButtonDefaults.buttonColors(containerColor = StatusGood),
                            modifier = Modifier.weight(1f),
                        ) { Text("Disable", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp) }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { vm.debloatAction("enable", dryRun = false, allowBatch = batchConfirmed || state.selected.size == 1) },
                            enabled = state.selected.isNotEmpty() && !state.busy,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.weight(1f),
                        ) { Text("Re-enable", color = MutedGold, fontSize = 12.sp) }
                        Button(
                            onClick = { vm.debloatAction("uninstall-user", dryRun = false, allowBatch = batchConfirmed || state.selected.size == 1) },
                            enabled = state.selected.isNotEmpty() && !state.busy &&
                                (state.selected.size == 1 || batchConfirmed),
                            colors = ButtonDefaults.buttonColors(containerColor = RiskHigh),
                            modifier = Modifier.weight(1f),
                        ) { Text("Uninstall (u0)", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp) }
                    }

                    state.lastResult?.let { (label, results) ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MutedGold)
                        results.take(6).forEach { r ->
                            Text(
                                "- ${r.command}: ${r.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (r.success) StatusGood else StatusBad,
                                fontSize = 11.sp,
                            )
                        }
                        if (results.size > 6) {
                            Text("...and ${results.size - 6} more", color = SilverDim, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // Package list
        items(filtered, key = { it.pkg.packageName }) { item ->
            val isBlocked = item.category == Recommendation.BLOCKED
            val isExpanded = expanded == item.pkg.packageName
            EisCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = if (isExpanded) null else item.pkg.packageName }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isBlocked) {
                            Checkbox(
                                checked = item.pkg.packageName in state.selected,
                                onCheckedChange = { vm.toggleDebloatSelection(item.pkg.packageName) },
                                colors = CheckboxDefaults.colors(checkedColor = MutedGold),
                            )
                        } else {
                            Spacer(modifier = Modifier.width(48.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.pkg.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                item.pkg.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = SilverDim,
                                fontSize = 11.sp,
                            )
                        }
                        RiskBadge(item.risk)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            item.category.name,
                            fontSize = 10.sp,
                            color = when (item.category) {
                                Recommendation.RECOMMENDED -> StatusGood
                                Recommendation.BLOCKED -> RiskHigh
                                else -> SilverDim
                            },
                        )
                        Text(
                            (if (item.pkg.isEnabled) "enabled" else "disabled") +
                                (if (item.pkg.isSystem) " / system" else " / user"),
                            fontSize = 10.sp,
                            color = SilverDim,
                        )
                    }
                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(6.dp))
                        DimLabel("ACTION: ${item.recommendedAction}")
                        Text("Purpose: ${item.profile?.purpose ?: "unknown / not in database"}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Reason: ${item.reason}", style = MaterialTheme.typography.bodySmall)
                        Text("Evidence: ${item.evidenceSource}", color = SilverDim,
                            style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                        Text("Reversal: ${item.reversal}", color = SilverDim,
                            style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                    }
                }
            }
        }

        item {
            TextButtonish("Export report (share/save via clipboard not included - shows in Safety)") {
                val report = vm.debloatReport()
                android.util.Log.i("EIS-Report", report)
            }
        }
    }
}

@Composable
private fun TextButtonish(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = MutedGold,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
