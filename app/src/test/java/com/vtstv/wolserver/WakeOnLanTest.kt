/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver

import com.vtstv.wolserver.core.engine.WakeOnLan
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for WakeOnLan engine: MAC parsing, formatting, validation,
 * and magic packet generation.
 */
class WakeOnLanTest {

    private lateinit var wakeOnLan: WakeOnLan

    @Before
    fun setUp() {
        wakeOnLan = WakeOnLan()
    }

    @Test
    fun testParseMacAddress_colonFormat() {
        val mac = "18:31:BF:6E:D5:BB"
        val bytes = wakeOnLan.parseMacAddress(mac)
        assertNotNull(bytes)
        assertEquals(6, bytes!!.size)
        assertEquals(0x18.toByte(), bytes[0])
        assertEquals(0x31.toByte(), bytes[1])
        assertEquals(0xBF.toByte(), bytes[2])
        assertEquals(0x6E.toByte(), bytes[3])
        assertEquals(0xD5.toByte(), bytes[4])
        assertEquals(0xBB.toByte(), bytes[5])
    }

    @Test
    fun testParseMacAddress_dashFormat() {
        val mac = "18-31-BF-6E-D5-BB"
        val bytes = wakeOnLan.parseMacAddress(mac)
        assertNotNull(bytes)
        assertEquals(6, bytes!!.size)
        assertEquals(0x18.toByte(), bytes[0])
        assertEquals(0xBB.toByte(), bytes[5])
    }

    @Test
    fun testParseMacAddress_rawHex() {
        val mac = "1831BF6ED5BB"
        val bytes = wakeOnLan.parseMacAddress(mac)
        assertNotNull(bytes)
        assertEquals(6, bytes!!.size)
    }

    @Test
    fun testParseMacAddress_invalidLength() {
        val mac = "18:31:BF:6E:D5" // Only 5 bytes
        val bytes = wakeOnLan.parseMacAddress(mac)
        assertNull(bytes)
    }

    @Test
    fun testParseMacAddress_invalidHexChars() {
        val mac = "GG:HH:II:JJ:KK:LL"
        val bytes = wakeOnLan.parseMacAddress(mac)
        assertNull(bytes)
    }

    @Test
    fun testFormatMacAddress() {
        assertEquals("18:31:BF:6E:D5:BB", wakeOnLan.formatMacAddress("1831bf6ed5bb"))
        assertEquals("AA:BB:CC:DD:EE:FF", wakeOnLan.formatMacAddress("aa-bb-cc-dd-ee-ff"))
    }

    @Test
    fun testIsValidMacAddress() {
        assertTrue(wakeOnLan.isValidMacAddress("18:31:BF:6E:D5:BB"))
        assertTrue(wakeOnLan.isValidMacAddress("18-31-bf-6e-d5-bb"))
        assertTrue(wakeOnLan.isValidMacAddress("1831bf6ed5bb"))
        assertFalse(wakeOnLan.isValidMacAddress("invalid_mac"))
        assertFalse(wakeOnLan.isValidMacAddress("12:34"))
        assertFalse(wakeOnLan.isValidMacAddress(""))
    }
}
