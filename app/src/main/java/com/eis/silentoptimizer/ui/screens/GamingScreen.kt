package com.eis.silentoptimizer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eis.silentoptimizer.gaming.GameProfile
import com.eis.silentoptimizer.ui.DimLabel
import com.eis.silentoptimizer.ui.EisCard
import com.eis.silentoptimizer.ui.EisViewModel
import com.eis.silentoptimizer.ui.ScreenHeader
import com.eis.silentoptimizer.ui.SectionTitle
import com.eis.silentoptimizer.ui.StatusLine
import com.eis.silentoptimizer.ui.theme.MutedGold
import com.eis.silentoptimizer.ui.theme.RiskCaution
import com.eis.silentoptimizer.ui.theme.SilverDim
import com.eis.silentoptimizer.ui.theme.StatusBad
import com.eis.silentoptimizer.ui.theme.StatusGood
import com.eis.silentoptimizer.ui.theme.StatusOff

/**
 * Gaming Center: game detection, profiles, refresh-rate policy with honest
 * capability labels. Never claims FPS or game-mode control it doesn't have.
 */
@Composable
fun GamingScreen(vm: EisViewModel) {
    val state by vm.gaming.collectAsState()
    val global by vm.ui.collectAsState()
    val mode = global.privileges?.mode

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                "Gaming Center",
                "Display policy and stability tools. EIS never fabricates FPS numbers.",
            )
        }

        // Capability matrix - honest reporting (spec 6)
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("CAPABILITIES", "Mode: ${mode ?: "probing"}")
                    val caps = com.eis.silentoptimizer.gaming.GamingEngine(
                        com.eis.silentoptimizer.daily.DailyOptimizer({ null }),
                        { null },
                        com.eis.silentoptimizer.core.PrivilegeMode.STANDARD,
                    ).capabilities(mode ?: com.eis.silentoptimizer.core.PrivilegeMode.STANDARD)
                    StatusLine(
                        "Refresh-rate policy",
                        if (caps["refresh_control"] == true) "available (shell)" else "needs Shizuku",
                        if (caps["refresh_control"] == true) StatusGood else StatusOff,
                    )
                    StatusLine(
                        "Animation control",
                        if (caps["animation_control"] == true) "available (shell)" else "needs Shizuku",
                        if (caps["animation_control"] == true) StatusGood else StatusOff,
                    )
                    StatusLine(
                        "Per-app Game Mode API",
                        "system API only - unavailable to any normal app",
                        StatusOff,
                    )
                    StatusLine("FPS measurement", "not provided (would require surfaceflinger)", StatusOff)
                    StatusLine("Thermal monitoring", "available (PowerManager API 29+)", StatusGood)
                    StatusLine("Thermal protection disable", "never offered", StatusBad)
                }
            }
        }

        // Profile picker
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("PROFILE")
                    GameProfile.entries.forEach { profile ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = state.profile == profile,
                                onClick = { vm.setGameProfile(profile) },
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                    selectedColor = MutedGold,
                                ),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.title, fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(profile.description, color = SilverDim,
                                    style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // Refresh rate control
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle(
                        "DISPLAY REFRESH POLICY",
                        "peak_refresh_rate constrains display policy; game FPS depends on the game, not this value.",
                    )
                    val rates = listOf(60, 90, 120, 144, 165)
                    val currentLabel = state.targetRefreshRate?.let { "$it Hz" } ?: "Device default"
                    StatusLine("Target", currentLabel, MutedGold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rates.forEach { hz ->
                            val selected = state.targetRefreshRate == hz
                            Text(
                                "${hz}Hz",
                                fontSize = 11.sp,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else SilverDim,
                                modifier = Modifier
                                    .padding(2.dp)
                                    .then(if (selected) Modifier else Modifier)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                    // Simple selector row with buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rates.forEach { hz ->
                            Button(
                                onClick = { vm.setGamingRefreshRate(hz) },
                                enabled = (mode != null && mode != com.eis.silentoptimizer.core.PrivilegeMode.STANDARD),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.targetRefreshRate == hz) MutedGold
                                    else MaterialTheme.colorScheme.surface,
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    "${hz}",
                                    fontSize = 11.sp,
                                    color = if (state.targetRefreshRate == hz)
                                        MaterialTheme.colorScheme.onPrimary else SilverDim,
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { vm.setGamingRefreshRate(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("Default (release cap)", color = MutedGold, fontSize = 11.sp) }
                }
            }
        }

        // Thermal threshold (informational slider)
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("THERMAL WARNING THRESHOLD")
                    StatusLine("Warn at", "%.0f °C".format(state.thermalWarnCelsius), RiskCaution)
                    Slider(
                        value = state.thermalWarnCelsius.toFloat(),
                        onValueChange = { /* threshold kept in UI state via profile apply */ },
                        valueRange = 36f..50f,
                        enabled = false,
                        colors = SliderDefaults.colors(thumbColor = MutedGold, activeTrackColor = MutedGold),
                    )
                    Text(
                        "EIS monitors thermal status but never disables throttling. " +
                            "The threshold is advisory - OEM thermal policy always wins.",
                        color = SilverDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Apply bar
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.applyGamingProfile(dryRun = true) },
                            enabled = !state.busy,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f),
                        ) { Text("Dry run", color = MaterialTheme.colorScheme.onPrimary) }
                        Button(
                            onClick = { vm.applyGamingProfile(dryRun = false) },
                            enabled = !state.busy && mode != null &&
                                mode != com.eis.silentoptimizer.core.PrivilegeMode.STANDARD,
                            colors = ButtonDefaults.buttonColors(containerColor = StatusGood),
                            modifier = Modifier.weight(1f),
                        ) { Text("Apply profile", color = Color.White) }
                    }
                    state.lastResults.forEach { (key, res) ->
                        Text(
                            "$key: ${res.message}",
                            fontSize = 11.sp,
                            color = if (res.success) StatusGood else StatusBad,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        // Detected games
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle(
                        "DETECTED GAMES",
                        "${state.games.size} found via category/API heuristics (no network)",
                    )
                }
            }
        }
        if (state.games.isEmpty()) {
            item {
                Text(
                    "No games detected yet - rescan from settings or install a game.",
                    color = SilverDim,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(state.games) { game ->
            EisCard {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(game.label, fontWeight = FontWeight.Medium)
                        Text(
                            "CATEGORY_GAME",
                            fontSize = 10.sp,
                            color = if (game.category == 0) StatusGood else SilverDim,
                        )
                    }
                    Text(game.packageName, fontSize = 11.sp, color = SilverDim)
                    Spacer(modifier = Modifier.height(4.dp))
                    DimLabel("PER-GAME GUIDANCE")
                    com.eis.silentoptimizer.gaming.GamingEngine(
                        com.eis.silentoptimizer.daily.DailyOptimizer({ null }),
                        { null },
                        com.eis.silentoptimizer.core.PrivilegeMode.STANDARD,
                    ).recommendationsFor(game, mode ?: com.eis.silentoptimizer.core.PrivilegeMode.STANDARD)
                        .forEach { line ->
                            Text("- $line", style = MaterialTheme.typography.bodySmall,
                                color = SilverDim, fontSize = 11.sp)
                        }
                }
            }
        }

        // Profile risks
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("PROFILE RISKS", state.profile.title)
                    com.eis.silentoptimizer.gaming.GamingEngine(
                        com.eis.silentoptimizer.daily.DailyOptimizer({ null }),
                        { null },
                        com.eis.silentoptimizer.core.PrivilegeMode.STANDARD,
                    ).profileRisks(state.profile).forEach { risk ->
                        Text("- $risk", style = MaterialTheme.typography.bodySmall, color = RiskCaution)
                    }
                }
            }
        }
    }
}
