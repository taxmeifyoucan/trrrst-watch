package dev.trrrst.watch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.trrrst.watch.City
import dev.trrrst.watch.WatchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityPicker(
    label: String,
    cities: List<City>,
    selected: City?,
    onSelect: (City) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (expanded) query else (selected?.let { "${it.name}  (UTC${fmtTz(it.tzMin)})" } ?: ""),
            onValueChange = { query = it; expanded = true },
            readOnly = !expanded,
            label = { Text(label) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
        )
        val filtered = remember(query, cities) {
            if (query.isBlank()) cities.take(60)
            else cities.filter { it.name.contains(query, true) }.take(60)
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            filtered.forEach { c ->
                DropdownMenuItem(
                    text = { Text("${c.name}  (UTC${fmtTz(c.tzMin)})") },
                    onClick = {
                        onSelect(c); query = ""; expanded = false
                    })
            }
        }
    }
}

fun fmtTz(min: Int): String {
    val sign = if (min < 0) "-" else "+"
    val a = kotlin.math.abs(min)
    return "%s%d:%02d".format(sign, a / 60, a % 60)
}

@Composable
fun WorldScreen(vm: WatchViewModel) {
    val st = vm.dstState
    if (st == null) {
        Box(Modifier.fillMaxSize().wrapContentSize()) { Text("Connect to the watch first") }
        return
    }
    var home by remember { mutableStateOf(vm.homeCities.find { it.no == st.homeCity }) }
    var world by remember { mutableStateOf(vm.worldCities.find { it.no == st.worldCity }) }
    var dstMode by remember(st) {
        mutableStateOf(if (st.homeAuto) 0 else if (st.homeDstOn) 1 else 2)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 10.dp)
    ) {
        item {
            Text("Home city", style = MaterialTheme.typography.titleSmall)
            CityPicker("Home city", vm.homeCities, home) { home = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Summer time")
                Spacer(Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    listOf("auto" to 0, "on" to 1, "off" to 2).forEachIndexed { i, (label, v) ->
                        SegmentedButton(
                            selected = dstMode == v,
                            onClick = { dstMode = v },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 3)
                        ) { Text(label) }
                    }
                }
            }
            Button(
                onClick = { home?.let { vm.saveHomeCity(it, dstMode) } },
                enabled = !vm.busy && home != null
            ) { Text("Save home city") }
        }
        item { HorizontalDivider() }
        item {
            Text("World time", style = MaterialTheme.typography.titleSmall)
            CityPicker("World city", vm.worldCities, world) { world = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { world?.let { vm.saveWorldCity(it, true) } },
                    enabled = !vm.busy && world != null
                ) { Text("Save world city") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { vm.swapCities() }, enabled = !vm.busy) {
                    Text("Swap home ↔ world")
                }
            }
            Text(
                "Home time is synchronized from your phone, so it should reflect your current location. " +
                    "World time is calculated as an offset from it. When travelling, let your phone " +
                    "change time zone first, then update your home city.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
