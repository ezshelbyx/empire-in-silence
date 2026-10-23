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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eis.silentoptimizer.ui.DimLabel
import com.eis.silentoptimizer.ui.Divider
import com.eis.silentoptimizer.ui.EisCard
import com.eis.silentoptimizer.ui.EisViewModel
import com.eis.silentoptimizer.ui.KeyValueRow
import com.eis.silentoptimizer.ui.ScreenHeader
import com.eis.silentoptimizer.ui.SectionTitle
import com.eis.silentoptimizer.ui.theme.MutedGold
import com.eis.silentoptimizer.ui.theme.SilverDim
import com.eis.silentoptimizer.ui.theme.StatusOff

/**
 * Settings: privacy switches, Shizuku guidance, about info. No network
 * settings exist because the app has no INTERNET permission.
 */
@Composable
fun SettingsScreen(vm: EisViewModel) {
    val state by vm.ui.collectAsState()
    val prefs = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    val dryRunDefault = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader("Settings", "Empire in Silence - Silent Authority. Maximum Control.")
        }

        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("SAFETY DEFAULTS")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Always preview dry-run first",
                                style = MaterialTheme.typography.bodyMedium)
                            Text("Shows exact commands before execution",
                                color = SilverDim, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                        }
                        Switch(
                            checked = dryRunDefault.value,
                            onCheckedChange = { dryRunDefault.value = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = MutedGold),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Local-only logging",
                                style = MaterialTheme.typography.bodyMedium)
                            Text("Journal never leaves the device (enforced: no INTERNET permission)",
                                color = SilverDim, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                        }
                        Switch(
                            checked = prefs.value,
                            onCheckedChange = { prefs.value = it },
                            enabled = false, // locked ON: telemetry is not a feature of this app
                            colors = SwitchDefaults.colors(checkedTrackColor = MutedGold),
                        )
                    }
                }
            }
        }

        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("PRIVILEGE MODE SETUP")
                    DimLabel("MODE A - STANDARD")
                    Text(
                        "Works out of the box: device intelligence, inventory, diagnostics, guidance. " +
                            "No permissions beyond package visibility.",
                        color = SilverDim, style = MaterialTheme.typography.bodySmall,
                    )
                    Divider()
                    DimLabel("MODE B - SHIZUKU")
                    Text(
                        "1. Install the Shizuku app (play.google.com/store/apps/details?id=moe.shizuku.privileged.api).\n" +
                            "2. Start Shizuku via wireless debugging (Android 11+) or adb: " +
                            "'adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh'.\n" +
                            "3. Tap 'Authorize Shizuku' in EIS and accept the prompt.\n" +
                            "4. Shizuku stops after reboot - start it again, then re-probe.",
                        color = SilverDim, style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.requestShizuku() },
                            enabled = state.privileges?.shizukuInstalled == true,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("Authorize Shizuku", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp) }
                        Button(
                            onClick = { vm.reprobePrivileges() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("Re-probe", color = MutedGold, fontSize = 12.sp) }
                    }
                    Divider()
                    DimLabel("MODE C - ROOT")
                    Text(
                        "Magisk and KernelSU are supported through their standard su contract. APatch is " +
                            "detected but marked unverified until confirmed on device. EIS asks for " +
                            "authorization through your root manager only - it never bypasses or " +
                            "pre-grants it. KernelSU may require 'Superuser' mode granted for shell.",
                        color = SilverDim, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            state.privileges?.notes?.let { notes ->
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle("STATUS NOTES")
                        notes.forEach { n ->
                            Text("- $n", color = SilverDim, style = MaterialTheme.typography.bodySmall)
                        }
                        if (notes.isEmpty()) {
                            Text("All privilege tiers as expected.",
                                color = SilverDim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("ABOUT")
                    KeyValueRow("App", "Empire in Silence (EIS)")
                    KeyValueRow("Package", "com.eis.silentoptimizer")
                    KeyValueRow("Version", com.eis.silentoptimizer.BuildConfig.VERSION_NAME)
                    KeyValueRow("Category", "Android System Optimization")
                    KeyValueRow("Network access", "None (no INTERNET permission)")
                    KeyValueRow("Analytics", "None")
                    KeyValueRow("Open-source credits",
                        "Shizuku (RikkaApps), libsu (topjohnwu), AOSP docs")
                    Divider()
                    Text(
                        "Disclaimer: system modification carries risk. EIS defaults to reversible, " +
                            "verified actions and blocks critical packages, but no tool can guarantee " +
                            "safety on every OEM build. Stability comes before aggressive tweaking.",
                        color = SilverDim, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp,
                    )
                }
            }
        }

        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("DIAGNOSTICS")
                    vm.componentReport().forEach { (k, v) -> KeyValueRow(k, v) }
                    Text(
                        "Runner mode for privileged actions: ${vm.runnerMode()}",
                        color = StatusOff, style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
