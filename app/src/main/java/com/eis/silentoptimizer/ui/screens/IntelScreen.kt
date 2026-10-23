package com.eis.silentoptimizer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eis.silentoptimizer.device.DeviceProbe
import com.eis.silentoptimizer.recommend.Priority
import com.eis.silentoptimizer.recommend.RecommendationEngine
import com.eis.silentoptimizer.ui.EisCard
import com.eis.silentoptimizer.ui.EisViewModel
import com.eis.silentoptimizer.ui.KeyValueRow
import com.eis.silentoptimizer.ui.ScreenHeader
import com.eis.silentoptimizer.ui.SectionTitle
import com.eis.silentoptimizer.ui.StatusLine
import com.eis.silentoptimizer.ui.theme.MutedGold
import com.eis.silentoptimizer.ui.theme.SilverDim
import com.eis.silentoptimizer.ui.theme.StatusGood
import com.eis.silentoptimizer.ui.theme.StatusOff

/**
 * Device Intelligence: full read-only device profile + priority selection
 * that drives the recommendation engine (spec 9, 11).
 */
@Composable
fun IntelScreen(vm: EisViewModel) {
    val state by vm.ui.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader("Device Intelligence", "Read-only device profile and recommendation priorities")
        }

        item {
            state.device?.let { d ->
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle("IDENTITY")
                        KeyValueRow("Manufacturer", d.manufacturer)
                        KeyValueRow("Brand", d.brand)
                        KeyValueRow("Model", d.model)
                        KeyValueRow("Device", d.device)
                        KeyValueRow("Product", d.product)
                        KeyValueRow("Hardware", d.hardware)
                        KeyValueRow("Android", "${d.androidVersion} (API ${d.sdkInt})")
                        d.securityPatch?.let { KeyValueRow("Security patch", it) }
                        KeyValueRow("Build", d.buildId)
                        KeyValueRow("Skin", RecommendationEngine.skinLabel(d.oemSkin))
                        KeyValueRow("Emulator", if (d.isEmulator) "yes" else "no")
                    }
                }
            }
        }

        item {
            state.device?.let { d ->
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle("HARDWARE")
                        KeyValueRow("ABI", d.supportedAbis.joinToString(", "))
                        KeyValueRow("CPU cores", d.cpuCores.toString())
                        KeyValueRow("RAM", DeviceProbe.formatBytes(d.totalRamBytes))
                        KeyValueRow(
                            "Storage free",
                            "${DeviceProbe.formatBytes(d.availableStorageBytes)} / " +
                                DeviceProbe.formatBytes(d.totalStorageBytes),
                        )
                        Text(
                            "GPU model is not exposed by public APIs; GPU devfreq nodes are visible in " +
                                "Root Lab when rooted. We do not guess GPU names from CPU hints.",
                            color = SilverDim, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            state.battery?.let { b ->
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle("BATTERY")
                        KeyValueRow("Level", "${b.levelPercent}%")
                        KeyValueRow("Charging", if (b.isCharging) "yes" else "no")
                        b.temperatureC?.let { KeyValueRow("Temperature", "%.1f °C".format(it)) }
                        b.voltageMv?.let { KeyValueRow("Voltage", "$it mV") }
                        b.technology?.let { KeyValueRow("Technology", it) }
                        KeyValueRow("Health", b.healthLabel)
                        b.chargeCounterUah?.let { KeyValueRow("Charge counter", "$it µAh") }
                    }
                }
            }
        }

        item {
            state.thermal?.let { t ->
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle("THERMAL")
                        StatusLine("PowerManager status", t.thermalStatusLabel, StatusOff)
                        if (t.zones.isEmpty()) {
                            Text(
                                "Zone readings unavailable without privilege (most production builds " +
                                    "restrict /sys/class/thermal). Root Lab reads them with root.",
                                color = SilverDim, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        t.zones.forEach { z ->
                            val temp = z.temperatureC
                            StatusLine(
                                z.name,
                                if (temp != null) "%.1f °C".format(temp) else "unavailable",
                                if (temp != null && temp >= 45.0) MutedGold else StatusGood,
                            )
                        }
                    }
                }
            }
        }

        // Priority selector
        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("RECOMMENDATION PRIORITIES", "Drives the dashboard recommendations")
                    Priority.entries.forEach { p ->
                        val checked = p in state.priorities
                        Text(
                            (if (checked) "[x] " else "[ ] ") + p.name.replace('_', ' '),
                            color = if (checked) MutedGold else SilverDim,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = if (checked) state.priorities - p
                                    else state.priorities + p
                                    vm.setPriorities(next)
                                }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }

        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("ENVIRONMENT NOTES")
                    Text(
                        "- Package visibility: Android 11+ filters installed-app queries; EIS declares " +
                            "QUERY_ALL_PACKAGES because full inventory is its core function (documented " +
                            "in SECURITY_AND_PRIVACY.md).",
                        color = SilverDim, style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "- No INTERNET permission: EIS performs zero network I/O by design, so no " +
                            "device data can be exfiltrated and remote content cannot trigger commands.",
                        color = SilverDim, style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "- Enterprise/carrier device owners are respected: EIS never attempts to " +
                            "remove or bypass device management policies.",
                        color = SilverDim, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
