package com.delicity.thermalprinter.adapters

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZebraAdapterTest {

    @Test
    fun `ecarte les imprimantes Bluetooth d'autres marques`() {
        assertTrue(ZebraAdapter.isForeignBluetoothPrinter("00:01:90:60:C3:B3", null)) // Epson par OUI
        assertTrue(ZebraAdapter.isForeignBluetoothPrinter("00:11:62:2E:08:16", null)) // Star par OUI
        assertTrue(ZebraAdapter.isForeignBluetoothPrinter("12:34:56:78:9A:BC", "TM-m30II_007731"))
        assertTrue(ZebraAdapter.isForeignBluetoothPrinter("12:34:56:78:9A:BC", "TSP100-K8672"))
        assertTrue(ZebraAdapter.isForeignBluetoothPrinter("00:01:02:03:0a:0b", "Inner Printer"))
    }

    @Test
    fun `garde les Zebra`() {
        assertFalse(ZebraAdapter.isForeignBluetoothPrinter("AC:3F:A4:12:34:56", "XXZKJ172300123"))
        assertFalse(ZebraAdapter.isForeignBluetoothPrinter("AC:3F:A4:12:34:56", "ZQ520"))
        assertFalse(ZebraAdapter.isForeignBluetoothPrinter("AC:3F:A4:12:34:56", "QLn320"))
        assertFalse(ZebraAdapter.isForeignBluetoothPrinter("AC:3F:A4:12:34:56", null))
    }
}
