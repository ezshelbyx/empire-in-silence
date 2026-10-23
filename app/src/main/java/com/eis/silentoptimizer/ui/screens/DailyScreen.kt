package com.eis.silentoptimizer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eis.silentoptimizer.core.OpResult
import com.eis.silentoptimizer.core.PrivilegeMode
import com.eis.silentoptimizer.device.DeviceProbe
import com.eis.silentoptimizer.ui.DimLabel
import com.eis.silentoptimizer.ui.EisCard
import com.eis.silentoptimizer.ui.EisViewModel
import com.eis.silentoptimizer.ui.KeyValueRow
import com.eis.silentoptimizer.ui.ScreenHeader
import com.eis.silentoptimizer.ui.SectionTitle
import com.eis.silentoptimizer.ui.StatusLine
import com.eis.silentoptimizer.ui.theme.MutedGold
import com.eis.silentoptimizer.ui.theme.SilverDim
import com.eis.silentoptimizer.ui.theme.StatusBad
import com.eis.silentoptimizer.ui.theme.StatusGood
import com.eis.silentoptimizer.ui.theme.StatusOff
import com.eis.silentoptimizer.ui.theme.StatusWarn

/**
 * Daily Optimization: stability/responsiveness/battery. Every card states
 * what it changes and whether it needs Shizuku/root; Standard mode gets
 * read-only diagnostics + honest guidance (spec 7).
 */
@Composable
fun DailyScreen(vm: EisViewModel) {
    val state by vm.ui.collectAsState()
    val mode = state.privileges?.mode ?: PrivilegeMode.STANDARD
    val hasShell = mode != PrivilegeMode.STANDARD
    var lastResult by remember { mutableStateOf<String?>(null) }
    var refreshResult by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                "Daily Optimization",
                "Stability, responsiveness, battery. Benefits are qualitative unless measured.",
            )
        }

        // --- Battery & thermal readout (always available) ---
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("BATTERY & THERMAL", "Read-only diagnostics")
                    state.battery?.let { b ->
                        KeyValueRow("Level", "${b.levelPercent}%")
                        KeyValueRow("Charging", if (b.isCharging) "Yes" else "No")
                        b.temperatureC?.let { KeyValueRow("Temperature", "%.1f °C".format(it)) }
                        b.voltageMv?.let { KeyValueRow("Voltage", "$it mV") }
                        KeyValueRow("Health", b.healthLabel)
                        b.currentNowUa?.let { KeyValueRow("Current now", "$it µA") }
                    }
                    state.thermal?.let { t ->
                        StatusLine(
                            "Thermal status",
                            t.thermalStatusLabel,
                            when {
                                t.thermalStatus >= 3 -> StatusBad
                                t.thermalStatus >= 1 -> StatusWarn
                                else -> StatusGood
                            },
                        )
                        Text(
                            "Throttling is a safety mechanism. EIS never disables it.",
                            color = SilverDim, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // --- Storage ---
        item {
            state.device?.let { d ->
                val freeRatio = d.availableStorageBytes.toDouble() / d.totalStorageBytes.coerceAtLeast(1)
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle("STORAGE")
                        KeyValueRow("Free", DeviceProbe.formatBytes(d.availableStorageBytes))
                        KeyValueRow("Total", DeviceProbe.formatBytes(d.totalStorageBytes))
                        StatusLine(
                            "Free ratio",
                            "%.1f%%".format(freeRatio * 100),
                            if (freeRatio < 0.1) StatusBad else if (freeRatio < 0.25) StatusWarn else StatusGood,
                        )
                        Text(
                            "Cleanup guidance: uninstall unused apps from Settings > Apps, clear large app " +
                                "caches via Settings > Storage, and move media off-device. EIS never clears " +
                                "app data without confirmation.",
                            color = SilverDim, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // --- Animation scales ---
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle(
                        "ANIMATION SCALES",
                        if (hasShell) "Applied via shell (settings put global), verified by read-back"
                        else "Requires Shizuku - in Standard mode set these in Settings > Developer options",
                    )
                    for (which in listOf("window", "transition", "animator")) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(which, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = SilverDim)
                            ScaleButton("0", enabled = hasShell) {
                                vm.applyAnimationScale(which, 0f, false) { r -> lastResult = r.message }
                            }
                            ScaleButton("0.5", enabled = hasShell) {
                                vm.applyAnimationScale(which, 0.5f, false) { r -> lastResult = r.message }
                            }
                            ScaleButton("1", enabled = hasShell) {
                                vm.applyAnimationScale(which, 1f, false) { r -> lastResult = r.message }
                            }
                        }
                    }
                    lastResult?.let {
                        Text(it, fontSize = 11.sp, color = MutedGold, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(
                        "Expected effect: shorter perceived transitions. Not a benchmarked speedup.",
                        color = SilverDim, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp,
                    )
                }
            }
        }

        // --- Refresh rate ---
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle(
                        "REFRESH RATE",
                        if (hasShell) "peak_refresh_rate (AOSP policy) - EIS verifies read-back"
                        else "Requires Shizuku/root; unsupported builds ignore this key",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(60, 90, 120, 144, 165).forEach { hz ->
                            Button(
                                onClick = {
                                    vm.applyRefreshRate(hz, false) { r -> refreshResult = r.message }
                                },
                                enabled = hasShell,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("$hz", color = MutedGold, fontSize = 11.sp) }
                        }
                    }
                    Button(
                        onClick = { vm.applyRefreshRate(null, false) { r -> refreshResult = r.message } },
                        enabled = hasShell,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("Release cap (default policy)", color = MutedGold, fontSize = 11.sp) }
                    refreshResult?.let {
                        Text(it, fontSize = 11.sp, color = MutedGold, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // --- Memory honesty ---
        item {
            state.device?.let { d ->
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle("MEMORY")
                        val am = androidx.compose.runtime.remember {
                            // Reading memory info is done once per composition via cached device profile.
                            d.totalRamBytes
                        }
                        KeyValueRow("Total RAM", DeviceProbe.formatBytes(am))
                        Text(
                            "EIS does not ship a RAM cleaner or task killer: Android's LMKD already " +
                                "manages reclaim, and killing background apps causes relaunch cost, not " +
                                "speed (Android memory management docs).",
                            color = SilverDim, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // --- Background / standby guidance ---
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("BACKGROUND & STARTUP GUIDANCE", "Mode-aware")
                    DimLabel(if (hasShell) "PRIVILEGED" else "STANDARD MODE")
                    Text(
                        "- Background limits: Settings > Apps > [app] > Battery > Restricted.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "- Startup: no public API grants autostart control; OEMs (MIUI/HiOS/ColorOS) " +
                            "provide their own autostart screens. EIS links there instead of faking it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "- App standby buckets appear in Settings > Apps > [app] > Mobile data & Wi-Fi " +
                            "where Usage Access is granted.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "- Network troubleshooting: Settings > System > Reset options > Reset Wi-Fi, " +
                            "mobile & Bluetooth (user action; EIS does not silently reset networking).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // --- Notifications/permissions review ---
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("NOTIFICATION & PERMISSION REVIEW")
                    val entries = state.privileges
                    Text(
                        "EIS opens system screens rather than silently toggling permissions: " +
                            "notification access and special app access are managed by Android's own UI.",
                        color = SilverDim, style = MaterialTheme.typography.bodySmall,
                    )
                    StatusLine(
                        "App inventory loaded",
                        "${state.installedCount} packages",
                        if (state.installedCount > 0) StatusGood else StatusOff,
                    )
                    entries?.let { Text("Mode for privileged actions: ${it.mode}",
                        color = SilverDim, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ScaleButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, color = if (enabled) MutedGold else SilverDim, fontSize = 11.sp)
    }
}
