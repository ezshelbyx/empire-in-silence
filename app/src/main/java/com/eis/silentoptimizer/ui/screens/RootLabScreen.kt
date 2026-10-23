package com.eis.silentoptimizer.ui.screens

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eis.silentoptimizer.ui.DimLabel
import com.eis.silentoptimizer.ui.EisCard
import com.eis.silentoptimizer.ui.EisViewModel
import com.eis.silentoptimizer.ui.KeyValueRow
import com.eis.silentoptimizer.ui.RiskBadge
import com.eis.silentoptimizer.ui.ScreenHeader
import com.eis.silentoptimizer.ui.SectionTitle
import com.eis.silentoptimizer.ui.StatusLine
import com.eis.silentoptimizer.ui.theme.MutedGold
import com.eis.silentoptimizer.ui.theme.RiskHigh
import com.eis.silentoptimizer.ui.theme.SilverDim
import com.eis.silentoptimizer.ui.theme.StatusBad
import com.eis.silentoptimizer.ui.theme.StatusGood
import com.eis.silentoptimizer.ui.theme.StatusOff
import com.eis.silentoptimizer.ui.theme.StatusWarn
import com.eis.silentoptimizer.core.RiskLevel

/**
 * Root Lab (spec 8): read-first advanced controls. Every control shows
 * current value, supported values, privilege, effect, risk, persistence,
 * reboot requirement and reset procedure before offering a write. Writes
 * are root-only, dry-run capable, and journaled with undo.
 */
@Composable
fun RootLabScreen(vm: EisViewModel) {
    val lab by vm.rootLab.collectAsState()
    var writeTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // path to previous
    var writeValue by remember { mutableStateOf("") }
    var writeMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                "Root Lab",
                "Read-first. No thermal/SELinux/verified-boot changes. Ever.",
            )
        }

        item {
            EisCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    SectionTitle("ACCESS")
                    StatusLine(
                        "Root manager",
                        if (lab.available) lab.manager else "root not active",
                        if (lab.available) StatusGood else StatusOff,
                    )
                    StatusLine(
                        "Modules",
                        if (lab.available) "${lab.modules.size} found" else "-",
                        StatusOff,
                    )
                    lab.message?.let {
                        Text(it, color = StatusWarn, style = MaterialTheme.typography.bodySmall)
                    }
                    if (lab.loading) {
                        Text("Reading sysfs...", color = SilverDim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Kernel info
        if (lab.available && lab.kernel.isNotEmpty()) {
            item {
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle("KERNEL")
                        lab.kernel.forEach { (k, v) ->
                            KeyValueRow(k, v.take(120))
                        }
                    }
                }
            }
        }

        // CPU controls
        if (lab.available) {
            items(lab.cpu, key = { it.path }) { control ->
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(control.label, fontWeight = FontWeight.Medium)
                            RiskBadge(control.risk)
                        }
                        DimLabel(control.path)
                        KeyValueRow("Current", control.currentValue ?: "unreadable")
                        control.supportedValues?.let { vals ->
                            KeyValueRow("Supported", vals.joinToString(" "))
                        }
                        KeyValueRow("Required", control.requiredPrivilege.name)
                        KeyValueRow("Persistence", control.persistence)
                        KeyValueRow("Reboot", if (control.rebootRequired) "required" else "not required")
                        Text("Effect: ${control.expectedEffect}",
                            style = MaterialTheme.typography.bodySmall, color = SilverDim)
                        Text("Reset: ${control.resetProcedure}",
                            style = MaterialTheme.typography.bodySmall, color = SilverDim)
                        if (control.writable && control.supportedValues != null && lab.available) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                control.supportedValues.take(5).forEach { v ->
                                    Button(
                                        onClick = {
                                            writeTarget = control.path to (control.currentValue ?: "")
                                            writeValue = v
                                            writeMessage = null
                                        },
                                        enabled = writeTarget?.first != control.path,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1f, fill = false),
                                    ) {
                                        Text(v, fontSize = 11.sp,
                                            color = if (control.currentValue == v) StatusGood else MutedGold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // GPU section
            if (lab.gpu.isNotEmpty()) {
                item {
                    EisCard {
                        Column(modifier = Modifier.padding(12.dp)) {
                            SectionTitle("GPU / DEVFREQ", "Read-only view. Overclocking is not offered.")
                            lab.gpu.forEach { g ->
                                Text("${g.label}: ${g.currentValue ?: "unreadable"}",
                                    style = MaterialTheme.typography.bodySmall)
                                g.supportedValues?.let {
                                    Text("  governors: ${it.joinToString(" ")}",
                                        style = MaterialTheme.typography.bodySmall, color = SilverDim, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Thermal zones
            if (lab.thermal.isNotEmpty()) {
                item {
                    EisCard {
                        Column(modifier = Modifier.padding(12.dp)) {
                            SectionTitle("THERMAL ZONES", "Monitoring only - never modified")
                            lab.thermal.take(12).forEach { z ->
                                val temp = z.celsius
                                StatusLine(
                                    z.zone,
                                    if (temp != null) "%.1f °C".format(temp) else "unavailable",
                                    when {
                                        temp == null -> StatusOff
                                        temp >= 48.0 -> StatusBad
                                        temp >= 42.0 -> StatusWarn
                                        else -> StatusGood
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // zRAM / IO
            item {
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle("MEMORY / ZRAM / IO")
                        if (lab.zram.isEmpty() && lab.io.isEmpty()) {
                            Text("No readable nodes (vendor kernels vary; privilege may be insufficient).",
                                color = SilverDim, style = MaterialTheme.typography.bodySmall)
                        }
                        lab.zram.forEach { (k, v) -> KeyValueRow(k, v.take(60)) }
                        lab.io.forEach { (k, v) -> KeyValueRow("$k scheduler", v.take(60)) }
                    }
                }
            }

            // Modules
            item {
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SectionTitle(
                            "ROOT MODULES",
                            "Inventory only. EIS never downloads or installs modules (spec 8).",
                        )
                        if (lab.modules.isEmpty()) {
                            Text("No modules found at known manager paths.",
                                color = SilverDim, style = MaterialTheme.typography.bodySmall)
                        }
                        lab.modules.forEach { m ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(m.name, fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("${m.id} @ ${m.flavor} - v${m.version}",
                                        color = SilverDim, fontSize = 10.sp)
                                }
                                Text(
                                    if (m.enabled) "ENABLED" else "DISABLED",
                                    color = if (m.enabled) StatusGood else StatusWarn,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Text(
                            "Third-party module safety: verify the source repo, check Android/arch " +
                                "compatibility and read the install script before flashing anywhere. " +
                                "A bad module can bootloop; recovery = disable it from recovery or the " +
                                "manager's safe mode.",
                            color = SilverDim, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp,
                        )
                    }
                }
            }

            // Write editor (appears after picking a supported value)
            writeTarget?.let { (path, previous) ->
                item {
                    EisCard {
                        Column(modifier = Modifier.padding(12.dp)) {
                            SectionTitle("CONFIRM WRITE", path)
                            KeyValueRow("Previous", previous)
                            OutlinedTextField(
                                value = writeValue,
                                onValueChange = { writeValue = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("New value (alphanumeric token)") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MutedGold,
                                ),
                            )
                            Text(
                                "RISK: HIGH - sysfs writes can destabilize the running system. " +
                                    "Reversible via journaled undo (previous value restored). " +
                                    "Not persistent across reboot unless a module persists it.",
                                color = RiskHigh, style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = {
                                        vm.writeSysfs(path, writeValue, previous, dryRun = true) { r ->
                                            writeMessage = "DRY RUN: ${r.command} -> ${r.message}"
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) { Text("Dry run", color = MaterialTheme.colorScheme.onPrimary) }
                                Button(
                                    onClick = {
                                        vm.writeSysfs(path, writeValue, previous, dryRun = false) { r ->
                                            writeMessage = (if (r.success) "OK: " else "FAILED: ") + r.message
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RiskHigh),
                                    modifier = Modifier.weight(1f),
                                ) { Text("Execute", color = Color.White) }
                                Button(
                                    onClick = { writeTarget = null; writeMessage = null },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                    ),
                                ) { Text("Cancel", color = SilverDim) }
                            }
                            writeMessage?.let {
                                Text(it, fontSize = 11.sp, color = MutedGold,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }

        if (!lab.available && !lab.loading) {
            item {
                EisCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Root Lab is locked. Install and authorize Magisk, KernelSU or APatch " +
                                "(APatch support is best-effort/unverified), then re-probe from the " +
                                "Dashboard. Standard and Shizuku modes continue to work without root.",
                            color = SilverDim, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
