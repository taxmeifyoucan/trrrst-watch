package dev.trrrst.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.trrrst.watch.data.StepStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Past-days chart with a point and area per day. Tap or drag to inspect. */
@Composable
fun DailyHistoryChart(days: List<StepStore.DayEntry>, modifier: Modifier = Modifier) {
    val shown = remember(days) { days.takeLast(14) }
    if (shown.isEmpty()) return
    var selectedIndex by remember(shown) { mutableIntStateOf(shown.lastIndex) }
    val selected = shown[selectedIndex]
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val selectionBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Past days activity", style = MaterialTheme.typography.titleSmall)
            if (selected.date == today) {
                Text(
                    "in progress",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            "${selected.date}  ·  %,d st  ·  %,d m".format(selected.steps, selected.distance),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        fun indexAt(x: Float, width: Float): Int = if (shown.size == 1) 0 else
            (x / (width / shown.lastIndex)).roundToInt().coerceIn(0, shown.lastIndex)

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .pointerInput(shown) {
                    detectTapGestures { offset ->
                        selectedIndex = indexAt(offset.x, size.width.toFloat())
                    }
                }
                .pointerInput(shown) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        selectedIndex = indexAt(change.position.x, size.width.toFloat())
                    }
                }
        ) {
            val maxSteps = shown.maxOf { it.steps }.coerceAtLeast(1)
            val baseline = size.height - 5f
            val interval = if (shown.lastIndex == 0) 0f else size.width / shown.lastIndex
            fun x(index: Int) = if (shown.lastIndex == 0) size.width / 2 else interval * index
            fun y(steps: Int) = baseline - (steps.toFloat() / maxSteps) * (size.height - 20f)

            // Soft area under the daily values.
            val area = Path().apply {
                moveTo(x(0), baseline)
                shown.forEachIndexed { index, day -> lineTo(x(index), y(day.steps)) }
                lineTo(x(shown.lastIndex), baseline)
                close()
            }
            drawPath(area, color = primary.copy(alpha = 0.13f))

            // Completed-day line; the segment into today is dashed.
            val solid = Path()
            shown.forEachIndexed { index, day ->
                if (index == 0) solid.moveTo(x(index), y(day.steps))
                else if (!(index == shown.lastIndex && day.date == today)) {
                    solid.lineTo(x(index), y(day.steps))
                }
            }
            drawPath(
                solid,
                color = primary,
                style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            if (shown.last().date == today && shown.size > 1) {
                val previous = shown.lastIndex - 1
                drawLine(
                    color = primary,
                    start = Offset(x(previous), y(shown[previous].steps)),
                    end = Offset(x(shown.lastIndex), y(shown.last().steps)),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))
                )
            }

            shown.forEachIndexed { index, day ->
                val center = Offset(x(index), y(day.steps))
                if (index == selectedIndex) drawCircle(selectionBg, radius = 13f, center = center)
                if (day.date == today) {
                    // Hollow point and dashed segment mark an unfinished day.
                    drawCircle(primary, radius = 6f, center = center, style = Stroke(width = 3f))
                } else {
                    drawCircle(
                        if (index == selectedIndex) primary else muted,
                        radius = if (index == selectedIndex) 6f else 4f,
                        center = center
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(shown.first().date.drop(5), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(shown.last().date.drop(5), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}
