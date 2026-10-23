package com.eis.silentoptimizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eis.silentoptimizer.ui.EisApp
import com.eis.silentoptimizer.ui.EisContextHolder
import com.eis.silentoptimizer.ui.EisViewModel
import com.eis.silentoptimizer.ui.EisViewModelFactory
import com.eis.silentoptimizer.ui.theme.EISTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EISApplication).container
        applicationContext.let { ctx ->
            // Context is attached to the ViewModel before first use.
            EisContextHolder.context = ctx
        }
        setContent {
            EISTheme {
                EISRoot(container)
            }
        }
    }
}

@Composable
private fun EISRoot(container: AppContainer) {
    val factory = remember { EisViewModelFactory(container) }
    val vm: EisViewModel = viewModel(factory = factory)
    EisApp(vm = vm)
}
