package dev.trrrst.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.trrrst.watch.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrrrstTheme {
                val vm: WatchViewModel = viewModel()
                var permitted by remember { mutableStateOf(hasPermissions()) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permitted = it.values.all { v -> v } }
                LaunchedEffect(Unit) {
                    if (!permitted) {
                        val perms = mutableListOf<String>()
                        if (Build.VERSION.SDK_INT >= 31) {
                            perms += Manifest.permission.BLUETOOTH_SCAN
                            perms += Manifest.permission.BLUETOOTH_CONNECT
                        } else {
                            perms += Manifest.permission.ACCESS_FINE_LOCATION
                        }
                        launcher.launch(perms.toTypedArray())
                    }
                }
                if (permitted) AppScaffold(vm)
                else Box(Modifier.fillMaxSize()) {
                    Text(
                        "TRRRST Watch needs Bluetooth permission",
                        modifier = Modifier.fillMaxSize().wrapContentSize()
                    )
                }
            }
        }
    }

    private fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

private enum class Tab(val label: String) { WATCH("Watch"), ALARMS("Alarms"), WORLD("World"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(vm: WatchViewModel) {
    var tab by remember { mutableStateOf(Tab.WATCH) }
    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(title = {
                    Text("TRRRST WATCH", style = MaterialTheme.typography.titleMedium)
                })
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it, onClick = { tab = it },
                        icon = {}, label = { Text(it.label) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.WATCH -> WatchHome(vm)
                Tab.ALARMS -> AlarmsScreen(vm)
                Tab.WORLD -> WorldScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}
