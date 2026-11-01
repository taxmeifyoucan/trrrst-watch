package dev.trrrst.watch

import android.app.Application
import android.content.Context
import android.media.RingtoneManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.trrrst.watch.ble.BleManager
import dev.trrrst.watch.ble.Protocol
import dev.trrrst.watch.data.StepStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class City(val no: Int, val name: String, val tzMin: Int, val dstMin: Int, val rule: Int)

class WatchViewModel(app: Application) : AndroidViewModel(app) {
    private val preferences = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    val ble = BleManager(app)
    val steps = StepStore(app)
    var accentIndex by mutableStateOf(preferences.getInt("accentIndex", 0))
        private set
    var stepsSnapshot by mutableStateOf(steps.last)
        private set
    var stepHistory by mutableStateOf(steps.history())
        private set

    var connState by mutableStateOf(BleManager.ConnState.DISCONNECTED)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var logLine by mutableStateOf("")

    // watch state
    var mac by mutableStateOf("")
    var version by mutableStateOf("")
    var battery by mutableStateOf(-1)
    var temperature by mutableStateOf(0)
    var findPhoneRinging by mutableStateOf(false)

    var alarms by mutableStateOf<List<Protocol.Alarm>?>(null)
    var hourlyChime by mutableStateOf(false)
    var basic by mutableStateOf<Protocol.Basic?>(null)
    var bleSettings by mutableStateOf<Protocol.BleSettings?>(null)
    var profile by mutableStateOf<Protocol.Profile?>(null)
    var timerRaw by mutableStateOf<ByteArray?>(null)
    var dstState by mutableStateOf<Protocol.DstState?>(null)
    var dstSettings by mutableStateOf<Map<Int, Protocol.DstSetting>>(emptyMap())

    var homeCities by mutableStateOf<List<City>>(emptyList())
    var worldCities by mutableStateOf<List<City>>(emptyList())

    init {
        viewModelScope.launch {
            ble.state.collectLatest { connState = it }
        }
        viewModelScope.launch {
            ble.statusLine.collectLatest { status = it }
        }
        viewModelScope.launch {
            ble.findPhoneEvents.collectLatest { ringing ->
                findPhoneRinging = ringing
                if (ringing) log("*** FIND PHONE ***")
                updateRingtone(ringing)
            }
        }
        viewModelScope.launch {
            ble.found.collectLatest { dev ->
                if (dev != null) {
                    if (ble.connect(dev)) refreshAll()
                }
            }
        }
        loadCities()
    }

    private fun loadCities() {
        fun parse(name: String): List<City> {
            val arr = org.json.JSONArray(
                getApplication<Application>().assets.open(name).bufferedReader().readText()
            )
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                City(
                    o.getInt("no"), o.getString("name"),
                    (o.getDouble("tz") * 60).toInt(), (o.getDouble("dst") * 60).toInt(), o.getInt("rule")
                )
            }
        }
        homeCities = parse("cities_ht.json")
        worldCities = parse("cities_wt.json")
    }

    fun log(msg: String) {
        logLine = msg
    }

    // ---- find-phone sound (phone side)
    private var ringtone: android.media.Ringtone? = null
    var finderSoundEnabled by mutableStateOf(true)
    var finderSoundType by mutableStateOf(android.media.RingtoneManager.TYPE_ALARM)

    private fun updateRingtone(ringing: Boolean) {
        if (ringing && finderSoundEnabled) {
            if (ringtone == null) {
                val uri = RingtoneManager.getDefaultUri(finderSoundType)
                ringtone = RingtoneManager.getRingtone(getApplication(), uri)
            }
            ringtone?.play()
        } else {
            ringtone?.stop()
        }
    }

    fun setFinderSound(type: Int) {
        finderSoundType = type
        ringtone = null
    }

    fun selectAccent(index: Int) {
        accentIndex = index
        preferences.edit().putInt("accentIndex", index).apply()
    }

    fun scanConnect() = ble.scan()

    fun disconnect() = ble.disconnect()

    private val operationMutex = Mutex()

    /** Queue complete operations in order. Every multi-command change runs as one
     *  unit, and user actions are never silently discarded. */
    private suspend fun <T> op(label: String, block: suspend () -> T): T? =
        operationMutex.withLock {
            busy = true
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.e("Trrrst", "$label failed", e)
                log("$label failed: ${e.message}")
                null
            } finally {
                busy = false
            }
        }

    fun refreshAll() {
        viewModelScope.launch {
            op("refresh") {
                val f = ble.read(Protocol.OP_BLE_FEATURES)
                mac = Protocol.decodeBleFeatures(f).first
                version = Protocol.decodeVersion(ble.read(Protocol.OP_VERSION))
                val cond = Protocol.decodeCondition(ble.read(Protocol.OP_CONDITION))
                battery = cond.first; temperature = cond.second
                alarms = Protocol.decodeAlarms(
                    ble.read(Protocol.OP_ALARM1), ble.read(Protocol.OP_ALARM2_5)
                )
                hourlyChime = (alarms!![0].rawFlags and 0x80) != 0
                basic = Protocol.Basic.decode(ble.read(Protocol.OP_BASIC))
                bleSettings = Protocol.BleSettings(ble.read(Protocol.OP_BLE_SETTINGS))
                profile = Protocol.Profile.decode(ble.read(Protocol.OP_EXERCISE))
                timerRaw = ble.read(Protocol.OP_TIMER)
                dstState = Protocol.DstState.decode(ble.read(Protocol.OP_DST_STATE))
                val s0 = Protocol.DstSetting.decode(ble.read(Protocol.OP_DST_SETTING))
                val s1 = Protocol.DstSetting.decode(ble.read(Protocol.OP_DST_SETTING))
                dstSettings = mapOf(s0.slot to s0, s1.slot to s1)
                log("watch state loaded")
            }
            // Populate steps on connect without consuming the watch's hourly history.
            fetchSteps(peek = true)
        }
    }

    fun syncTime() {
        viewModelScope.launch {
            op("time sync") {
                ble.write(Protocol.encodeTime())
                log("time synced")
            }
        }
    }

    fun fetchSteps(peek: Boolean = false) {
        viewModelScope.launch {
            op("steps") {
                val raw = ble.receive(Protocol.CAT_EXERCISE, commit = !peek)
                if (raw.isNotEmpty()) {
                    steps.ingest(Protocol.decodeStepRecord(raw))
                    stepsSnapshot = steps.last
                    stepHistory = steps.history()
                    log(if (peek) "steps read (kept on watch)" else "steps downloaded")
                } else log("no step data")
            }
        }
    }

    fun saveAlarms(list: List<Protocol.Alarm>, chime: Boolean) {
        viewModelScope.launch {
            op("alarms") {
                val current = Protocol.decodeAlarms(
                    ble.read(Protocol.OP_ALARM1), ble.read(Protocol.OP_ALARM2_5)
                )
                // Preserve hardware/reserved flags from the live watch state.
                val merged = list.mapIndexed { i, alarm ->
                    alarm.copy(rawFlags = current[i].rawFlags)
                }
                val (a15, a16) = Protocol.encodeAlarms(merged, chime)
                ble.write(a15); ble.write(a16)
                alarms = Protocol.decodeAlarms(ble.read(Protocol.OP_ALARM1), ble.read(Protocol.OP_ALARM2_5))
                hourlyChime = (alarms!![0].rawFlags and 0x80) != 0
                log("alarms saved")
            }
        }
    }

    /** Read-modify-write at write time: every change starts from the *current*
     *  watch state, so rapid/concurrent toggles can't clobber each other. */
    fun saveBasic(change: (Protocol.Basic) -> Protocol.Basic) {
        viewModelScope.launch {
            op("settings") {
                val cur = Protocol.Basic.decode(ble.read(Protocol.OP_BASIC))
                ble.write(change(cur).encode())
                // official app ends settings sessions with a time write: forces the
                // display to re-render with the new settings (24h etc.)
                ble.write(Protocol.encodeTime())
                basic = Protocol.Basic.decode(ble.read(Protocol.OP_BASIC))
                log("settings saved")
            }
        }
    }

    fun saveAutoSync(hour: Int, minute: Int, enabled: Boolean) {
        viewModelScope.launch {
            op("auto-sync") {
                val current = Protocol.BleSettings(ble.read(Protocol.OP_BLE_SETTINGS))
                ble.write(current.withAutoSync(hour, minute, enabled))
                bleSettings = Protocol.BleSettings(ble.read(Protocol.OP_BLE_SETTINGS))
                log("auto-sync saved")
            }
        }
    }

    fun saveConnPeriod(minutes: Int) {
        viewModelScope.launch {
            op("connection time") {
                val current = Protocol.BleSettings(ble.read(Protocol.OP_BLE_SETTINGS))
                ble.write(current.withPeriod(minutes))
                bleSettings = Protocol.BleSettings(ble.read(Protocol.OP_BLE_SETTINGS))
                log("connection time saved")
            }
        }
    }

    fun saveProfile(goal: Int, height: Int, weight: Int) {
        viewModelScope.launch {
            op("goal") {
                val cur = Protocol.Profile.decode(ble.read(Protocol.OP_EXERCISE))
                ble.write(cur.copy(stepGoal = goal, height = height, weight = weight).encode())
                profile = Protocol.Profile.decode(ble.read(Protocol.OP_EXERCISE))
                log("goal saved")
            }
        }
    }

    fun saveTimer(h: Int, m: Int, s: Int) {
        viewModelScope.launch {
            op("timer") {
                val current = ble.read(Protocol.OP_TIMER)
                ble.write(Protocol.encodeTimer(h, m, s, current))
                timerRaw = ble.read(Protocol.OP_TIMER)
                log("timer saved")
            }
        }
    }

    /** home city + DST mode (initial-sync style: 1D + both 1E, then time) */
    fun saveHomeCity(city: City, dstMode: Int) {   // 0=auto,1=on,2=off
        viewModelScope.launch {
            op("home city") {
                val st = Protocol.DstState.decode(ble.read(Protocol.OP_DST_STATE))
                // Read both slots before writing, preserving live world-city parameters.
                ble.read(Protocol.OP_DST_SETTING) // current home slot, replaced below
                val world = Protocol.DstSetting.decode(ble.read(Protocol.OP_DST_SETTING))
                st.homeCity = city.no
                st.homeAuto = dstMode == 0
                st.homeDstOn = if (dstMode == 0) st.homeDstOn else dstMode == 1
                ble.write(st.encode())
                val home = Protocol.DstSetting(0, city.no, city.tzMin, city.dstMin, city.rule)
                ble.write(home.encode())
                ble.write(world.encode())
                ble.write(Protocol.encodeTime())
                dstState = Protocol.DstState.decode(ble.read(Protocol.OP_DST_STATE))
                dstSettings = mapOf(0 to home, 1 to world)
                log("home city saved")
            }
        }
    }

    /** world city change: official transaction type 1 */
    fun saveWorldCity(city: City, dstOn: Boolean) {
        viewModelScope.launch {
            op("world city") {
                ble.write(byteArrayOf(Protocol.OP_DST_TRANSACTION, 0, 1))
                val st = Protocol.DstState.decode(ble.read(Protocol.OP_DST_STATE))
                st.worldCity = city.no; st.worldAuto = true; st.worldDstOn = dstOn
                ble.write(st.encode())
                val s0 = Protocol.DstSetting.decode(ble.read(Protocol.OP_DST_SETTING))
                ble.read(Protocol.OP_DST_SETTING) // slot 1 read, discarded
                ble.write(s0.encode())
                val world = Protocol.DstSetting(1, city.no, city.tzMin, city.dstMin, city.rule)
                ble.write(world.encode())
                ble.write(byteArrayOf(Protocol.OP_DST_TRANSACTION, 1, 1))
                dstState = Protocol.DstState.decode(ble.read(Protocol.OP_DST_STATE))
                dstSettings = mapOf(0 to s0, 1 to world)
                log("world city saved")
            }
        }
    }

    /** summer time mode: official transaction type 3 (0=auto,1=on,2=off) */
    fun saveDstMode(mode: Int) {
        viewModelScope.launch {
            op("summer time") {
                ble.write(byteArrayOf(Protocol.OP_DST_TRANSACTION, 0, 3))
                val st = Protocol.DstState.decode(ble.read(Protocol.OP_DST_STATE))
                st.homeAuto = mode == 0
                if (mode != 0) st.homeDstOn = mode == 1
                ble.write(st.encode())
                repeat(2) {
                    val s = Protocol.DstSetting.decode(ble.read(Protocol.OP_DST_SETTING))
                    ble.write(s.encode())
                }
                ble.write(byteArrayOf(Protocol.OP_DST_TRANSACTION, 1, 3))
                dstState = Protocol.DstState.decode(ble.read(Protocol.OP_DST_STATE))
                log("summer time saved")
            }
        }
    }

    /** home <-> world swap: official transaction type 2 */
    fun swapCities() {
        viewModelScope.launch {
            op("swap") {
                ble.write(byteArrayOf(Protocol.OP_DST_TRANSACTION, 0, 2))
                val st = Protocol.DstState.decode(ble.read(Protocol.OP_DST_STATE))
                val tmp = st.homeCity; st.homeCity = st.worldCity; st.worldCity = tmp
                ble.write(st.encode())
                val s0 = Protocol.DstSetting.decode(ble.read(Protocol.OP_DST_SETTING))
                val s1 = Protocol.DstSetting.decode(ble.read(Protocol.OP_DST_SETTING))
                val home = Protocol.DstSetting(0, s1.city, s1.utcMin, s1.dstMin, s1.rule)
                val world = Protocol.DstSetting(1, s0.city, s0.utcMin, s0.dstMin, s0.rule)
                ble.write(home.encode())
                ble.write(world.encode())
                ble.write(byteArrayOf(Protocol.OP_DST_TRANSACTION, 1, 2))
                dstState = Protocol.DstState.decode(ble.read(Protocol.OP_DST_STATE))
                dstSettings = mapOf(0 to home, 1 to world)
                log("home ↔ world swapped")
            }
        }
    }
}
