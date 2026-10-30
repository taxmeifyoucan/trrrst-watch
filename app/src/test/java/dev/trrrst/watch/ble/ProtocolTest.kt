package dev.trrrst.watch.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun basicSettingsCanSetAndClearEveryManagedBit() {
        // Actual watch state captured before the bug: 24h on, tone off, power saving off.
        val initial = Protocol.Basic.decode(hex("131700000000000000000000"))
        assertArrayEquals(hex("131600000000000000000000"),
            initial.copy(h24 = false).encode())
        assertArrayEquals(hex("130700000000000000000000"),
            initial.copy(powerSaving = true).encode())
        assertArrayEquals(hex("131500000000000000000000"),
            initial.copy(buttonTone = true).encode())
        assertArrayEquals(hex("131700000000000000000000"), initial.encode())
    }

    @Test
    fun basicSettingsMatchOfficialAppWrites() {
        val official = Protocol.Basic.decode(hex("130700000000000000000000"))
        assertArrayEquals(hex("130600000000000000000000"), official.copy(h24 = false).encode())
        assertArrayEquals(hex("131700000000000000000000"), official.copy(powerSaving = false).encode())
        assertArrayEquals(hex("130701000000000000000000"), official.copy(lightLong = true).encode())
    }

    @Test
    fun stepRecordMatchesCapturedWatchValues() {
        val text = requireNotNull(javaClass.getResource("/step-record.hex")).readText().trim()
        val rec = Protocol.decodeStepRecord(hex(text))
        assertEquals(273, rec.todaySteps)
        assertEquals(100, rec.todayDistance)
        assertEquals(273, rec.hourly.sumOf { it.steps } + rec.currentBuckets.sum())
        assertEquals(2026, rec.timestamp.get(java.util.Calendar.YEAR))
    }

    @Test
    fun alarmModesAndHourlySignalMatchProtocol() {
        val alarms = (0 until 5).map { Protocol.Alarm(enabled = false, hour = 0, minute = 0) }
            .toMutableList()
        alarms[0] = Protocol.Alarm(enabled = true, hour = 10, minute = 30)
        alarms[1] = Protocol.Alarm(enabled = true, hour = 12, minute = 0, oneTime = true)
        alarms[2] = Protocol.Alarm(enabled = true, hour = 6, minute = 0, snooze = true)

        val (daily, _) = Protocol.encodeAlarms(alarms, hourlyChime = false)
        val (chime, rest) = Protocol.encodeAlarms(alarms, hourlyChime = true)
        assertEquals(0x40, daily[1].toInt())
        assertEquals(0xc0, chime[1].toInt() and 0xFF)
        assertEquals(0x60, rest[1].toInt())  // one-time alarm 2
        assertEquals(0x50, rest[5].toInt())  // snooze alarm 3

        val decoded = Protocol.decodeAlarms(chime, rest)
        assertEquals(true, decoded[0].enabled)
        assertEquals(true, decoded[1].oneTime)
        assertEquals(true, decoded[2].snooze)
    }

    @Test
    fun bleSettingsPreserveUnrelatedBytes() {
        val initial = Protocol.BleSettings(hex("110f0f0f0600500004000100001e03"))
        val disabled = initial.withAutoSync(hour = 6, minute = 45, enabled = false)
        assertEquals(0x86, disabled[12].toInt() and 0xFF)
        assertEquals(45, disabled[13].toInt())
        assertEquals(10, initial.withPeriod(10)[14].toInt())
        assertArrayEquals(initial.raw, initial.withAutoSync(0, 30, enabled = true))
    }

    @Test
    fun timerMatchesOfficialAppWrite() {
        val current = hex("18000a000000000000000000000000")
        assertArrayEquals(
            hex("180000060000000000000000000000"),
            Protocol.encodeTimer(0, 0, 6, current)
        )
    }

    @Test
    fun dstSettingsMatchOfficialAppWrites() {
        val world = Protocol.DstSetting(slot = 1, city = 84, utcMin = -420, dstMin = 60, rule = 1)
        assertArrayEquals(hex("1e015400e40401"), world.encode())
        assertEquals(world, Protocol.DstSetting.decode(world.encode()))

        val captured = hex("1d00010303f3765400ffffffffffff")
        val state = Protocol.DstState.decode(captured)
        assertEquals(30451, state.homeCity)
        assertEquals(84, state.worldCity)
        assertEquals(true, state.homeAuto)
        assertEquals(true, state.homeDstOn)
        assertArrayEquals(captured, state.encode())
    }
}
