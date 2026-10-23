package com.eis.silentoptimizer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.eis.silentoptimizer.ui.screens.DailyScreen
import com.eis.silentoptimizer.ui.screens.DebloatScreen
import com.eis.silentoptimizer.ui.screens.DashboardScreen
import com.eis.silentoptimizer.ui.screens.GamingScreen
import com.eis.silentoptimizer.ui.screens.IntelScreen
import com.eis.silentoptimizer.ui.screens.RootLabScreen
import com.eis.silentoptimizer.ui.screens.SafetyScreen
import com.eis.silentoptimizer.ui.screens.SettingsScreen
import com.eis.silentoptimizer.ui.theme.Charcoal
import com.eis.silentoptimizer.ui.theme.MutedGold
import com.eis.silentoptimizer.ui.theme.SilverDim

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Dest("dashboard", "Home", Icons.Filled.Dashboard),
    Dest("debloat", "Debloat", Icons.Filled.Apps),
    Dest("gaming", "Gaming", Icons.Filled.SportsEsports),
    Dest("daily", "Daily", Icons.Filled.Speed),
    Dest("rootlab", "Root Lab", Icons.Filled.Memory),
    Dest("intel", "Intel", Icons.Filled.Info),
    Dest("safety", "Safety", Icons.Filled.Security),
    Dest("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun EisApp(vm: EisViewModel) {
    val navController: NavHostController = rememberNavController()
    var currentRoute by rememberSaveable { mutableStateOf("dashboard") }

    Scaffold(
        containerColor = Charcoal,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                destinations.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            currentRoute = dest.route
                            navController.navigate(dest.route) {
                                popUpTo("dashboard") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = {
                            Text(
                                dest.label,
                                fontSize = 10.sp,
                                fontWeight = if (currentRoute == dest.route) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MutedGold,
                            selectedTextColor = MutedGold,
                            unselectedIconColor = SilverDim,
                            unselectedTextColor = SilverDim,
                            indicatorColor = Charcoal,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable("dashboard") { DashboardScreen(vm) }
            composable("debloat") {
                LaunchedEffect(Unit) { vm.scanDebloat() }
                DebloatScreen(vm)
            }
            composable("gaming") {
                LaunchedEffect(Unit) { vm.detectGames() }
                GamingScreen(vm)
            }
            composable("daily") { DailyScreen(vm) }
            composable("rootlab") {
                LaunchedEffect(Unit) { vm.loadRootLab() }
                RootLabScreen(vm)
            }
            composable("intel") { IntelScreen(vm) }
            composable("safety") { SafetyScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.sp,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SilverDim,
            )
        }
    }
}
