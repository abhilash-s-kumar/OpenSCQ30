package com.oppzippy.openscq30.features.fastpair

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class FastPairBatteryReaderTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // Payloads captured from a Soundcore P40i (A3955): 03 03 00 03 <left> <right> <case>
    @Test
    fun parsesChargingEarbuds() {
        val battery = FastPairBatteryReader.parseBattery(bytes(0xD8, 0xD6, 0xE4))!!
        assertEquals(FastPairBatteryValue(88, true), battery.left)
        assertEquals(FastPairBatteryValue(86, true), battery.right)
        assertEquals(FastPairBatteryValue(100, true), battery.case)
    }

    @Test
    fun parsesDischargingEarbuds() {
        val battery = FastPairBatteryReader.parseBattery(bytes(0x56, 0x53, 0xE4))!!
        assertEquals(FastPairBatteryValue(86, false), battery.left)
        assertEquals(FastPairBatteryValue(83, false), battery.right)
    }

    @Test
    fun unknownValueIsNull() {
        val battery = FastPairBatteryReader.parseBattery(bytes(0x50, 0x7F, 0xFF))!!
        assertEquals(FastPairBatteryValue(80, false), battery.left)
        assertNull(battery.right)
        assertNull(battery.case)
    }

    @Test
    fun singleComponent() {
        val battery = FastPairBatteryReader.parseBattery(bytes(0x40))!!
        assertEquals(FastPairBatteryValue(64, false), battery.left)
        assertNull(battery.right)
    }

    @Test
    fun rejectsUnexpectedLength() {
        assertNull(FastPairBatteryReader.parseBattery(bytes(0x40, 0x40)))
    }
}
