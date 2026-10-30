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
}
