package dev.trrrst.watch.data

import android.content.Context
import dev.trrrst.watch.ble.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Step history and latest snapshot persisted locally (offline, no account). */
class StepStore(context: Context) {
    private val file = File(context.filesDir, "steps.json")
    private val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val tf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    data class DayEntry(val date: String, val steps: Int, val distance: Int)
    data class Snapshot(
        val fetchedAt: String, val watchTime: String,
        val todaySteps: Int, val todayDistance: Int,
        val hourly: Map<Int, Int>, val daily: List<DayEntry>
    )

    var last: Snapshot? = null
        private set
    private val days = linkedMapOf<String, DayEntry>()

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("days") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                days[o.getString("date")] =
                    DayEntry(o.getString("date"), o.getInt("steps"), o.getInt("distance"))
            }
            val history = days.values.sortedBy { it.date }.takeLast(30)
            val saved = root.optJSONObject("last")
            last = if (saved != null) {
                val hourly = mutableMapOf<Int, Int>()
                saved.optJSONObject("hourly")?.let { h ->
                    h.keys().forEach { key -> hourly[key.toInt()] = h.getInt(key) }
                }
                Snapshot(
                    saved.optString("fetchedAt"), saved.optString("watchTime"),
                    saved.getInt("todaySteps"), saved.getInt("todayDistance"), hourly, history
                )
            } else {
                // Upgrade files written by older versions, which stored only days.
                history.lastOrNull()?.let {
                    Snapshot("", "", it.steps, it.distance, emptyMap(), history)
                }
            }
        }
    }

    @Synchronized
    fun ingest(rec: Protocol.StepRecord) {
        val date = df.format(rec.timestamp.time)
        days[date] = DayEntry(date, rec.todaySteps, rec.todayDistance)
        rec.daily.forEach { d ->
            if (d.steps != null) {
                val c = rec.timestamp.clone() as Calendar
                c.add(Calendar.DAY_OF_YEAR, -d.daysAgo)
                val k = df.format(c.time)
                days[k] = DayEntry(k, d.steps, d.distance ?: 0)
            }
        }
        val hourly = rec.hourly.associate { it.hourOfDay to it.steps }
        last = Snapshot(
            tf.format(Calendar.getInstance().time), tf.format(rec.timestamp.time),
            rec.todaySteps, rec.todayDistance, hourly,
            days.values.sortedBy { it.date }.takeLast(30)
        )
        save()
    }

    fun history(): List<DayEntry> = days.values.sortedBy { it.date }

    private fun save() {
        val arr = JSONArray()
        days.values.sortedBy { it.date }.forEach {
            arr.put(JSONObject().put("date", it.date).put("steps", it.steps).put("distance", it.distance))
        }
        val root = JSONObject().put("days", arr)
        last?.let { snap ->
            val hourly = JSONObject()
            snap.hourly.forEach { (hour, steps) -> hourly.put(hour.toString(), steps) }
            root.put("last", JSONObject()
                .put("fetchedAt", snap.fetchedAt)
                .put("watchTime", snap.watchTime)
                .put("todaySteps", snap.todaySteps)
                .put("todayDistance", snap.todayDistance)
                .put("hourly", hourly))
        }
        file.writeText(root.toString())
    }
}
