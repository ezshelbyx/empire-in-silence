package com.eis.silentoptimizer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.device.DeviceProbe
import com.eis.silentoptimizer.recommend.RecommendationEngine
import com.eis.silentoptimizer.ui.DimLabel
import com.eis.silentoptimizer.ui.EisCard
import com.eis.silentoptimizer.ui.EisViewModel
import com.eis.silentoptimizer.ui.KeyValueRow
import com.eis.silentoptimizer.ui.RiskBadge
import com.eis.silentoptimizer.ui.ScreenHeader
import com.eis.silentoptimizer.ui.SectionTitle
import com.eis.silentoptimizer.ui.StatusLine
import com.eis.silentoptimizer.ui.theme.SilverDim
import com.eis.silentoptimizer.ui.theme.StatusBad
import com.eis.silentoptimizer.ui.theme.StatusGood
import com.eis.silentoptimizer.ui.theme.StatusOff
import com.eis.silentoptimizer.ui.theme.StatusWarn

@Composable
fun DashboardScreen(vm: EisViewModel) {
    val state by vm.ui.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                "Empire in Silence",
                "Silent Authority. Maximum Control.",
            )
        }

        // --- Device identity ---
        item {
            state.device?.let { d ->
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle(
                            "DEVICE",
                            "${d.manufacturer} ${d.model} - Android ${d.androidVersion} (API ${d.sdkInt})",
                        )
                        KeyValueRow("Skin profile", RecommendationEngine.skinLabel(d.oemSkin))
                        KeyValueRow("Build", d.buildId)
                        KeyValueRow("ABI", d.supportedAbis.joinToString(", "))
                        KeyValueRow("CPU cores", d.cpuCores.toString())
                        KeyValueRow(
                            "RAM",
                            "${DeviceProbe.formatBytes(d.totalRamBytes)} total",
                        )
                        KeyValueRow(
                            "Storage",
                            "${DeviceProbe.formatBytes(d.availableStorageBytes)} free / " +
                                DeviceProbe.formatBytes(d.totalStorageBytes),
                        )
                        d.securityPatch?.let { KeyValueRow("Security patch", it) }
                        if (d.isEmulator) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Emulator detected - results may not reflect real hardware.",
                                color = StatusWarn,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        // --- Privilege status ---
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("PRIVILEGE STATUS")
                    val p = state.privileges
                    if (p == null) {
                        Text("Probing...", color = SilverDim, style = MaterialTheme.typography.bodySmall)
                    } else {
                        StatusLine(
                            "Mode",
                            when (p.mode) {
                                PrivilegeMode.STANDARD -> "Standard (no elevated access)"
                                PrivilegeMode.SHIZUKU -> "Shizuku (shell identity)"
                                PrivilegeMode.ROOT -> "Root (${p.rootManager})"
                            },
                            when (p.mode) {
                                PrivilegeMode.STANDARD -> StatusWarn
                                PrivilegeMode.SHIZUKU -> StatusGood
                                PrivilegeMode.ROOT -> StatusGood
                            },
                        )
                        StatusLine(
                            "Shizuku",
                            when {
                                !p.shizukuInstalled -> "not installed"
                                !p.shizukuRunning -> "not running (restart after reboot)"
                                !p.shizukuAuthorized -> "running, unauthorized"
                                else -> "authorized"
                            },
                            if (p.shizukuAuthorized) StatusGood
                            else if (p.shizukuRunning) StatusWarn else StatusOff,
                        )
                        StatusLine(
                            "Root",
                            when {
                                p.rootGranted -> "granted"
                                p.rootAvailable -> "detected, not granted"
                                else -> "unavailable"
                            },
                            if (p.rootGranted) StatusGood
                            else if (p.rootAvailable) StatusWarn else StatusOff,
                        )
                        p.notes.forEach { note ->
                            Text(
                                "- $note",
                                color = SilverDim,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (p.shizukuInstalled && !p.shizukuAuthorized) {
                                Button(
                                    onClick = { vm.requestShizuku() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                    ),
                                ) { Text("Authorize Shizuku", color = MaterialTheme.colorScheme.onPrimary) }
                            }
                            TextButton(onClick = { vm.reprobePrivileges() }) {
                                Text("Re-probe", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        // --- Live status: battery/thermal/packages ---
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("LIVE STATUS")
                    state.battery?.let { b ->
                        StatusLine(
                            "Battery",
                            "${b.levelPercent}% ${if (b.isCharging) "- charging" else "- on battery"}" +
                                (b.temperatureC?.let { " - %.1f°C".format(it) } ?: ""),
                            if (b.temperatureC != null && b.temperatureC >= 40.0) StatusWarn else StatusGood,
                        )
                        StatusLine("Battery health", b.healthLabel, StatusOff)
                    }
                    state.thermal?.let { t ->
                        StatusLine(
                            "Thermal",
                            t.thermalStatusLabel,
                            if (t.thermalStatus >= 3) StatusBad
                            else if (t.thermalStatus >= 1) StatusWarn else StatusGood,
                        )
                        val withTemp = t.zones.filter { it.temperatureC != null }
                        if (withTemp.isNotEmpty()) {
                            val hottest = withTemp.maxByOrNull { it.temperatureC ?: 0.0 }
                            if (hottest != null) {
                                StatusLine(
                                    "Hottest zone",
                                    "${hottest.name}: %.1f°C".format(hottest.temperatureC),
                                    if ((hottest.temperatureC ?: 0.0) >= 45.0) StatusWarn else StatusOff,
                                )
                            }
                        } else {
                            StatusLine("Thermal zones", "unreadable (privilege required)", StatusOff)
                        }
                    }
                    StatusLine("Packages", "${state.installedCount} visible", StatusOff)
                    StatusLine("System / disabled", "${state.systemCount} / ${state.disabledCount}", StatusOff)
                }
            }
        }

        // --- Recent actions ---
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("RECENT ACTIONS")
                    if (state.recentActions.isEmpty()) {
                        Text(
                            "No changes made yet.",
                            color = SilverDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        state.recentActions.forEach { a ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (a.success) "OK" else "FAIL",
                                    color = if (a.success) StatusGood else StatusBad,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(34.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${a.action} -> ${a.target}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        a.timestampLabel,
                                        color = SilverDim,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Recommendations ---
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle(
                        "RECOMMENDED",
                        "Evidence-based; confidence shown per item. No numerical performance claims.",
                    )
                    if (state.recommendations.isEmpty()) {
                        Text(
                            "Nothing urgent detected for your current priorities.",
                            color = SilverDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        items(state.recommendations) { rec ->
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            rec.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        RiskBadge(rec.risk)
                    }
                    DimLabel(
                        "CONFIDENCE ${rec.confidence} | NEEDS ${rec.requiredMode} | ${rec.compatibility}",
                    )
                    Text("Problem: ${rec.problem}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Evidence: ${rec.evidence}", color = SilverDim, style = MaterialTheme.typography.bodySmall)
                    if (rec.evidenceUrl.isNotBlank()) {
                        Text(rec.evidenceUrl, color = StatusWarn, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Benefit: ${rec.expectedBenefit}", style = MaterialTheme.typography.bodySmall)
                    Text("Reversal: ${rec.reversal}", color = SilverDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
