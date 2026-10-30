package dev.trrrst.watch.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

/** Casio F-B100W (module 3589) protocol. Port of the validated Python codec;
 *  layouts confirmed against decompiled official app + full HCI captures. */
object Protocol {
    const val SERVICE = "26eb000d-b012-49a8-b1f8-394fb2032b0f"
    const val CHR_READ_REQ = "26eb002c-b012-49a8-b1f8-394fb2032b0f"
    const val CHR_ALL_FEATURES = "26eb002d-b012-49a8-b1f8-394fb2032b0f"
    const val CHR_DATA_REQ = "26eb0023-b012-49a8-b1f8-394fb2032b0f"
    const val CHR_CONVOY = "26eb0024-b012-49a8-b1f8-394fb2032b0f"

    const val OP_LINK_ALERT: Byte = 0x01
    const val OP_TIME: Byte = 0x09
    const val OP_ALERT: Byte = 0x0A
    const val OP_BLE_FEATURES: Byte = 0x10
    const val OP_BLE_SETTINGS: Byte = 0x11
    const val OP_BASIC: Byte = 0x13
    const val OP_ALARM1: Byte = 0x15
    const val OP_ALARM2_5: Byte = 0x16
    const val OP_TIMER: Byte = 0x18
    const val OP_DST_STATE: Byte = 0x1D
    const val OP_DST_SETTING: Byte = 0x1E
    const val OP_VERSION: Byte = 0x20
    const val OP_DST_TRANSACTION: Byte = 0x21
    const val OP_APP_INFO: Byte = 0x22
    const val OP_WATCH_NAME: Byte = 0x23
    const val OP_MODULE_ID: Byte = 0x26
    const val OP_CONDITION: Byte = 0x28
    const val OP_EXERCISE: Byte = 0x2D

    const val CAT_STOPWATCH = 0x00
    const val CAT_WATCH_LOG = 0x05
    const val CAT_EXERCISE = 0x11

    const val REASON_PAIRING = 0
    const val REASON_RECONNECT = 1
    const val REASON_FIND_PHONE = 2
    const val REASON_AUTO_SYNC = 3
    const val REASON_GET_TIME = 4

    fun drsp(cmd: Int, cat: Int, len: Int = 0): ByteArray =
        byteArrayOf(cmd.toByte(), cat.toByte(), len.toByte(), (len shr 8).toByte(), (len shr 16).toByte())

    fun parseDrsp(b: ByteArray): Triple<Int, Int, Int> =
        Triple(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF,
            (b[2].toInt() and 0xFF) or (b[3].toInt() and 0xFF shl 8) or (b[4].toInt() and 0xFF shl 16))

    // ---- 0x09 time: year LE16, m,d,h,m,s, dow 1=Mon..7=Sun, frac=ms*256/1000, reason 1
    fun encodeTime(cal: Calendar = Calendar.getInstance(), reason: Int = 1): ByteArray {
        val frac = cal.get(Calendar.MILLISECOND) * 256 / 1000
        val dow = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1   // Calendar Sun=1 -> 7
        return byteArrayOf(
            OP_TIME,
            (cal.get(Calendar.YEAR) and 0xFF).toByte(), (cal.get(Calendar.YEAR) shr 8).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(), cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(), cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte(), dow.toByte(), frac.toByte(), reason.toByte()
        )
    }

    // ---- 0x1D DST state (14 bytes after opcode)
    data class DstState(
        var homeDstOn: Boolean, var homeAuto: Boolean,
        var worldDstOn: Boolean, var worldAuto: Boolean,
        var homeCity: Int, var worldCity: Int
    ) {
        fun encode(): ByteArray {
            val hs = (if (homeDstOn) 1 else 0) or (if (homeAuto) 2 else 0)
            val ws = (if (worldDstOn) 1 else 0) or (if (worldAuto) 2 else 0)
            val bb = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(OP_DST_STATE).put(0).put(1).put(hs.toByte()).put(ws.toByte())
            bb.putShort(homeCity.toShort()).putShort(worldCity.toShort())
            repeat(6) { bb.put(0xFF.toByte()) }
            return bb.array()
        }

        companion object {
            fun decode(b: ByteArray): DstState {
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(3)
                val hs = bb.get().toInt(); val ws = bb.get().toInt()
                val hc = bb.short.toInt() and 0xFFFF; val wc = bb.short.toInt() and 0xFFFF
                return DstState(hs and 1 != 0, hs and 2 != 0, ws and 1 != 0, ws and 2 != 0, hc, wc)
            }
        }
    }

    // ---- 0x1E DST setting (6 bytes after opcode); offsets in 15-min units (int8 signed)
    data class DstSetting(val slot: Int, val city: Int, val utcMin: Int, val dstMin: Int, val rule: Int) {
        fun encode(): ByteArray {
            val bb = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(OP_DST_SETTING).put(slot.toByte()).putShort(city.toShort())
                .put((utcMin / 15).toByte()).put((dstMin / 15).toByte()).put(rule.toByte())
            return bb.array()
        }

        companion object {
            fun decode(b: ByteArray): DstSetting {
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(1)
                return DstSetting(
                    bb.get().toInt() and 0xFF, bb.short.toInt() and 0xFFFF,
                    bb.get().toInt() * 15, (bb.get().toInt() and 0xFF) * 15, bb.get().toInt() and 0xFF
                )
            }
        }
    }

    // ---- 0x15/0x16 alarms: 5 x [flags, 0x40, hour, min]; flags: 0x40 daily, 0x60 once, 0x50 snooze; alarm1 bit7 = hourly signal
    data class Alarm(
        var enabled: Boolean, var hour: Int, var minute: Int,
        var oneTime: Boolean = false, var snooze: Boolean = false, var rawFlags: Int = 0
    ) {
        fun encode(): ByteArray {
            var f = rawFlags and 0x8F
            if (enabled) f = f or (if (oneTime) 0x60 else if (snooze) 0x50 else 0x40)
            return byteArrayOf(f.toByte(), 0x40, hour.toByte(), minute.toByte())
        }
    }

    fun decodeAlarms(a15: ByteArray, a16: ByteArray): List<Alarm> {
        val blk = a15.copyOfRange(1, 5) + a16.copyOfRange(1, 17)
        return (0 until 20 step 4).map { i ->
            val f = blk[i].toInt() and 0xFF
            Alarm(f and 0x40 != 0, blk[i + 2].toInt() and 0xFF, blk[i + 3].toInt() and 0xFF,
                f and 0x20 != 0, f and 0x10 != 0 && f and 0x20 == 0, f)
        }
    }

    fun encodeAlarms(alarms: List<Alarm>, hourlyChime: Boolean? = null): Pair<ByteArray, ByteArray> {
        val blk = alarms.flatMap { it.encode().toList() }.toMutableList()
        if (hourlyChime != null)
            blk[0] = (if (hourlyChime) blk[0].toInt() or 0x80 else blk[0].toInt() and 0x7F).toByte()
        return Pair(
            byteArrayOf(OP_ALARM1) + blk.subList(0, 4).toByteArray(),
            byteArrayOf(OP_ALARM2_5) + blk.subList(4, 20).toByteArray()
        )
    }

    // ---- 0x13 basic settings (11 bytes after opcode)
    data class Basic(
        var h24: Boolean, var buttonTone: Boolean, var autoLight: Boolean, var powerSaving: Boolean,
        var lightLong: Boolean, var dateOrder: Int, var language: Int, var raw: ByteArray
    ) {
        fun encode(): ByteArray {
            val b = raw.copyOf()
            // Clear the managed bits first, then set them from the requested state.
            // Keeping 0x17 here made every existing bit impossible to clear.
            b[1] = ((b[1].toInt() and 0xE8) or
                    (if (h24) 0x01 else 0) or (if (buttonTone) 0 else 0x02) or
                    (if (autoLight) 0 else 0x04) or (if (powerSaving) 0 else 0x10)).toByte()
            b[2] = ((b[2].toInt() and 0xF0) or (if (lightLong) 1 else 0)).toByte()
            b[4] = dateOrder.toByte(); b[5] = language.toByte()
            return b
        }

        companion object {
            fun decode(b: ByteArray) = Basic(
                b[1].toInt() and 0x01 != 0, b[1].toInt() and 0x02 == 0,
                b[1].toInt() and 0x04 == 0, b[1].toInt() and 0x10 == 0,
                b[2].toInt() and 0x0F == 1, b[4].toInt() and 0xFF, b[5].toInt() and 0xFF, b
            )
        }
    }

    // ---- 0x11 BLE settings (14 bytes after opcode); byte12: hour + bit7 = disabled; byte13: min; byte14: conn period
    data class BleSettings(val raw: ByteArray) {
        val autoSyncEnabled get() = raw[12].toInt() and 0x80 == 0
        val autoSyncHour get() = raw[12].toInt() and 0x7F
        val autoSyncMinute get() = raw[13].toInt() and 0xFF
        val connectingPeriod get() = raw[14].toInt() and 0xFF

        fun withAutoSync(hour: Int, minute: Int, enabled: Boolean): ByteArray {
            val b = raw.copyOf()
            b[12] = ((hour and 0x7F) or (if (enabled) 0 else 0x80)).toByte()
            b[13] = minute.toByte()
            return b
        }

        fun withPeriod(minutes: Int): ByteArray {
            val b = raw.copyOf(); b[14] = minutes.toByte(); return b
        }
    }

    // ---- 0x2D exercise profile: goal BCD-LE 4B, height/weight BCD-LE bit-inverted 2B
    data class Profile(var stepGoal: Int, var height: Int, var weight: Int, val raw: ByteArray = ByteArray(0)) {
        fun encode(): ByteArray {
            val b = if (raw.size == 10) raw.copyOf() else ByteArray(10)
            b[0] = OP_EXERCISE
            val g = toBcdLe(stepGoal, 4); System.arraycopy(g, 0, b, 2, 4)
            val h = toBcdLe(height, 2).map { (it.toInt() xor 0xFF).toByte() }
            val w = toBcdLe(weight, 2).map { (it.toInt() xor 0xFF).toByte() }
            b[6] = h[0]; b[7] = h[1]; b[8] = w[0]; b[9] = w[1]
            return b
        }

        companion object {
            private fun bcdLe(bs: ByteArray, off: Int, n: Int): Int {
                var v = 0; var mul = 1
                for (i in 0 until n) {
                    val x = bs[off + i].toInt() and 0xFF
                    v += ((x shr 4) * 10 + (x and 0xF)) * mul; mul *= 100
                }
                return v
            }

            fun decode(b: ByteArray) = Profile(
                bcdLe(b, 2, 4),
                bcdLe(byteArrayOf((b[6].toInt() xor 0xFF).toByte(), (b[7].toInt() xor 0xFF).toByte()), 0, 2),
                bcdLe(byteArrayOf((b[8].toInt() xor 0xFF).toByte(), (b[9].toInt() xor 0xFF).toByte()), 0, 2), b
            )
        }
    }

    private fun toBcdLe(value: Int, n: Int): ByteArray {
        var v = value
        return ByteArray(n) {
            val r = v % 100; v /= 100
            ((r / 10) shl 4 or (r % 10)).toByte()
        }
    }

    private fun bcd(x: Int) = (x shr 4) * 10 + (x and 0xF)

    // ---- 0x18 timer: h, m, s binary at raw[1..3]
    fun encodeTimer(h: Int, m: Int, s: Int, raw: ByteArray): ByteArray {
        val b = if (raw.size == 15) raw.copyOf() else ByteArray(15)
        b[0] = OP_TIMER; b[1] = h.toByte(); b[2] = m.toByte(); b[3] = s.toByte()
        return b
    }

    // ---- misc decoders
    fun decodeBleFeatures(b: ByteArray): Pair<String, Int> {
        val mac = (1..6).reversed().joinToString(":") { "%02X".format(b[it]) }
        return Pair(mac, b[8].toInt() and 0xFF)
    }

    fun decodeVersion(b: ByteArray): String =
        "DST data ${b[1]}.${b[2]} · watch firmware ${b[3]}.${b[4]} · BLE firmware 0x${(b[13].toInt() and 0xFF).toString(16)}"

    fun decodeCondition(b: ByteArray): Triple<Int, Int, Int> =
        Triple(b[1].toInt() and 0xFF, b[2].toInt(), (b[5].toInt() and 0xFF) or (b[6].toInt() and 0xFF shl 8))

    // ---- step record (cat 0x11, 400 B)
    class StepRecord(
        val timestamp: Calendar, val todaySteps: Int, val todayDistance: Int,
        val hourly: List<Hour>, val currentBuckets: List<Int>, val daily: List<Day>
    ) {
        class Hour(val hourOfDay: Int, val steps: Int, val distance: Int)
        class Day(val daysAgo: Int, val steps: Int?, val distance: Int?)
    }

    fun decodeStepRecord(b: ByteArray): StepRecord {
        require(b.size >= 400 && b[0] == 0x26.toByte())
        val ts = Calendar.getInstance().apply {
            set(2000 + bcd(b[0].toInt()), bcd(b[1].toInt()) - 1, bcd(b[2].toInt()),
                bcd(b[3].toInt()), bcd(b[4].toInt()), bcd(b[5].toInt()))
            set(Calendar.MILLISECOND, 0)
        }
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or (b[o + 1].toInt() and 0xFF shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        val hourly = (0 until 24).map { h ->
            val buckets = (0 until 5).map { k -> u16(6 + h * 10 + k * 2) }
            val steps = if (buckets.all { it == 0xFFFE }) 0 else buckets.filter { it != 0xFFFE }.sum()
            val dist = u16(246 + h * 2)
            StepRecord.Hour((ts.get(Calendar.HOUR_OF_DAY) - h - 1 + 48) % 24, steps, if (dist == 0xFFFE) 0 else dist)
        }
        val daily = (0 until 7).map { d ->
            val s = u32(318 + d * 8); val m = u32(322 + d * 8)
            StepRecord.Day(d + 1,
                if (s == 0xFFFFFFFE.toInt() || s == -2) null else s,
                if (m == 0xFFFFFFFE.toInt() || m == -2) null else m)
        }
        val current = (0 until 5).map { u16(382 + it * 2) }.map { if (it == 0xFFFE) 0 else it }
        return StepRecord(ts, u32(374), u32(378), hourly.filter { it.steps > 0 }, current, daily)
    }
}
