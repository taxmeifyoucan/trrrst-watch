package dev.trrrst.watch.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.trrrst.watch.WatchViewModel

private val DATE_ORDERS = listOf("YMD", "YDM", "MDY", "DMY")
private val LANGS = listOf("EN", "ES", "FR", "DE", "IT", "RU")
private val PERIODS = listOf(3, 5, 10, 60, 90, 120)

@Composable
fun SettingsScreen(vm: WatchViewModel) {
    val ctx = LocalContext.current
    val b = vm.basic
    val bs = vm.bleSettings
    val prof = vm.profile
    val timer = vm.timerRaw
    if (b == null || bs == null || prof == null || timer == null) {
        Box(Modifier.fillMaxSize().wrapContentSize()) { Text("Connect to the watch first") }
        return
    }


    var period by remember(bs) { mutableStateOf(bs.connectingPeriod) }
    var autoOn by remember(bs) { mutableStateOf(bs.autoSyncEnabled) }
    var autoH by remember(bs) { mutableStateOf(bs.autoSyncHour) }
    var autoM by remember(bs) { mutableStateOf(bs.autoSyncMinute) }
    var goal by remember(prof) { mutableStateOf(prof.stepGoal.toString()) }
    var height by remember(prof) { mutableStateOf(prof.height.toString()) }
    var weight by remember(prof) { mutableStateOf(prof.weight.toString()) }
    var th by remember(timer) { mutableStateOf(timer[1].toInt()) }
    var tm by remember(timer) { mutableStateOf(timer[2].toInt()) }
    var ts by remember(timer) { mutableStateOf(timer[3].toInt()) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 10.dp)
    ) {
        item { Text("Connected watch info", style = MaterialTheme.typography.titleSmall) }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CASIO F-B100W", style = MaterialTheme.typography.bodyLarge)
                    Text("MAC ${vm.mac}", style = MaterialTheme.typography.bodySmall)
                    Text(vm.version, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Watch", style = MaterialTheme.typography.titleSmall) }
        item { SettingSwitch("24-hour display", b.h24) { vm.saveBasic { c -> c.copy(h24 = it) } } }
        item { SettingSwitch("Key sounds", b.buttonTone) { vm.saveBasic { c -> c.copy(buttonTone = it) } } }
        item { SettingSwitch("Power saving", b.powerSaving) { vm.saveBasic { c -> c.copy(powerSaving = it) } } }
        item { SettingSwitch("Long light (3 s)", b.lightLong) { vm.saveBasic { c -> c.copy(lightLong = it) } } }
        item {
            ChoiceRow("Date order", DATE_ORDERS, b.dateOrder) { vm.saveBasic { c -> c.copy(dateOrder = it) } }
            ChoiceRow("Language", LANGS, b.language) { vm.saveBasic { c -> c.copy(language = it) } }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Find phone", style = MaterialTheme.typography.titleSmall) }
        item {
            SettingSwitch("Play sound on this phone", vm.finderSoundEnabled) { vm.finderSoundEnabled = it }
            ChoiceRow(
                "Sound", listOf("Alarm", "Notification", "Ringtone"),
                when (vm.finderSoundType) {
                    android.media.RingtoneManager.TYPE_NOTIFICATION -> 1
                    android.media.RingtoneManager.TYPE_RINGTONE -> 2
                    else -> 0
                }
            ) {
                vm.setFinderSound(
                    when (it) {
                        1 -> android.media.RingtoneManager.TYPE_NOTIFICATION
                        2 -> android.media.RingtoneManager.TYPE_RINGTONE
                        else -> android.media.RingtoneManager.TYPE_ALARM
                    }
                )
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Connection", style = MaterialTheme.typography.titleSmall) }
        item {
            ChoiceRow("Connection time", PERIODS.map { "$it min" }, PERIODS.indexOf(period).coerceAtLeast(0)) {
                period = PERIODS[it]
                vm.saveConnPeriod(period)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto time sync")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    TimePickerDialog(ctx, { _, h, m -> autoH = h; autoM = m }, autoH, autoM, true).show()
                }) { Mono("%02d:%02d".format(autoH, autoM)) }
                Switch(checked = autoOn, onCheckedChange = {
                    autoOn = it; vm.saveAutoSync(autoH, autoM, it)
                })
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Profile & goal", style = MaterialTheme.typography.titleSmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Goal", goal, Modifier.weight(1f)) { goal = it }
                NumField("Height", height, Modifier.weight(1f)) { height = it }
                NumField("Weight", weight, Modifier.weight(1f)) { weight = it }
            }
            Button(onClick = {
                vm.saveProfile(
                    goal.toIntOrNull() ?: 10000,
                    height.toIntOrNull() ?: 0,
                    weight.toIntOrNull() ?: 0
                )
            }, enabled = !vm.busy) { Text("Save profile") }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Timer", style = MaterialTheme.typography.titleSmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("H", th.toString(), Modifier.weight(1f)) { th = it.toIntOrNull() ?: 0 }
                NumField("M", tm.toString(), Modifier.weight(1f)) { tm = it.toIntOrNull() ?: 0 }
                NumField("S", ts.toString(), Modifier.weight(1f)) { ts = it.toIntOrNull() ?: 0 }
            }
            Button(onClick = { vm.saveTimer(th, tm, ts) }, enabled = !vm.busy) { Text("Save timer") }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Appearance", style = MaterialTheme.typography.titleSmall) }
        item { AccentColorPicker(vm) }
    }
}

@Composable
private fun AccentColorPicker(vm: WatchViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Accent color")
        Spacer(Modifier.weight(1f))
        AccentColors.forEachIndexed { index, color ->
            val selected = vm.accentIndex == index
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (selected) 2.dp else 0.dp,
                        color = if (selected) Color.White else Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable { vm.selectAccent(index) }
            )
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Spacer(Modifier.weight(1f))
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(options.getOrElse(selected) { "?" })
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(i); open = false })
                }
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, mod: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
        label = { Text(label) }, modifier = mod,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}
