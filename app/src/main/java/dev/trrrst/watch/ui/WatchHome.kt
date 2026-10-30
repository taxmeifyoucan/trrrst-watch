package dev.trrrst.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trrrst.watch.WatchViewModel
import dev.trrrst.watch.ble.BleManager

@Composable
fun Mono(text: String, large: Boolean = false, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.bodyLarge,
        color = color
    )
}

@Composable
fun WatchHome(vm: WatchViewModel) {
    val connected = vm.connState == BleManager.ConnState.READY
    val snap = vm.stepsSnapshot
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp)
    ) {
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CASIO F-B100W", style = MaterialTheme.typography.titleSmall)
                    Text(vm.mac.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall)
                    Text(
                        vm.version.ifEmpty { "not connected" },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (connected) {
                        Text(
                            "battery raw ${vm.battery} · ${vm.temperature}°C",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (vm.logLine.isNotEmpty()) Text(vm.logLine, style = MaterialTheme.typography.bodySmall)
                    if (vm.findPhoneRinging) Text("FIND PHONE — ringing", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!connected) {
                    Button(onClick = { vm.scanConnect() }, enabled = vm.connState != BleManager.ConnState.CONNECTING) {
                        Text(if (vm.connState == BleManager.ConnState.SCANNING) "Scanning…" else "Connect")
                    }
                } else {
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
                    Button(onClick = { vm.syncTime() }) { Text("Sync time") }
                }
            }
        }
        if (connected) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Steps today", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { vm.fetchSteps() }) { Text("Download") }
                        }
                        Mono("%6d".format(snap?.todaySteps ?: 0), large = true, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "distance ${snap?.todayDistance ?: 0} m" +
                                    (snap?.watchTime?.let { " · watch $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        val hourly = snap?.hourly ?: emptyMap()
                        if (hourly.isNotEmpty()) HourlyChart(hourly)
                    }
                }
            }
            val hist = vm.stepHistory.takeLast(14).reversed()
            if (hist.isNotEmpty()) {
                item { Text("History", style = MaterialTheme.typography.titleSmall) }
                items(hist) { d ->
                    ListItem(
                        headlineContent = { Mono(d.date) },
                        trailingContent = { Mono("%,d st · %,d m".format(d.steps, d.distance)) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun HourlyChart(hourly: Map<Int, Int>) {
    val accent = MaterialTheme.colorScheme.primary
    val max = (hourly.values.maxOrNull() ?: 1).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val bw = size.width / 24f
        for (h in 0..23) {
            val v = hourly[h] ?: 0
            val bh = if (v == 0) 2f else (v.toFloat() / max) * (size.height - 14f)
            drawLine(
                accent,
                Offset(bw * h + bw / 2, size.height - 2f),
                Offset(bw * h + bw / 2, size.height - bh),
                strokeWidth = bw * 0.55f
            )
        }
    }
}
