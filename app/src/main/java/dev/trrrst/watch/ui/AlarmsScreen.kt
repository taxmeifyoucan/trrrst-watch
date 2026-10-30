package dev.trrrst.watch.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.trrrst.watch.WatchViewModel
import dev.trrrst.watch.ble.Protocol

@Composable
fun AlarmsScreen(vm: WatchViewModel) {
    val ctx = LocalContext.current
    val src = vm.alarms
    if (src == null) {
        Box(Modifier.fillMaxSize().wrapContentSize()) {
            Text("Connect to the watch first")
        }
        return
    }
    var list by remember(src) { mutableStateOf(src.map { it.copy() }) }
    var chime by remember(vm.hourlyChime) { mutableStateOf(vm.hourlyChime) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 10.dp)
    ) {
        itemsIndexed(list) { i, a ->
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        TimePickerDialog(ctx, { _, h, m ->
                            list = list.toMutableList().also {
                                it[i] = it[i].copy(hour = h, minute = m)
                            }
                        }, a.hour, a.minute, true).show()
                    }) { Mono("%02d:%02d".format(a.hour, a.minute)) }
                    Spacer(Modifier.weight(1f))
                    FilterChip(
                        selected = a.oneTime,
                        onClick = {
                            list = list.toMutableList()
                                .also { it[i] = it[i].copy(oneTime = !a.oneTime, snooze = false) }
                        },
                        label = { Text("once") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = a.snooze,
                        onClick = {
                            list = list.toMutableList()
                                .also { it[i] = it[i].copy(snooze = !a.snooze, oneTime = false) }
                        },
                        label = { Text("snooze") })
                    Switch(
                        checked = a.enabled,
                        onCheckedChange = { on ->
                            list = list.toMutableList().also { it[i] = it[i].copy(enabled = on) }
                        })
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hourly time signal")
                Spacer(Modifier.weight(1f))
                Switch(checked = chime, onCheckedChange = { chime = it })
            }
        }
        item {
            Button(onClick = { vm.saveAlarms(list, chime) }, enabled = !vm.busy) {
                Text("Save to watch")
            }
        }
    }
}
