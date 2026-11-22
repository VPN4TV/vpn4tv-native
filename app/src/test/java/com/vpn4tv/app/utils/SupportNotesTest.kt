package com.vpn4tv.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SupportNotesTest {
    @Before
    fun empty() = SupportNotes.clear()

    private fun texts() = SupportNotes.snapshot().map { it.substringAfter(' ').substringAfter(' ') }

    @Test
    fun linesCarryTheDeviceClockAndStayInOrder() {
        SupportNotes.add("network: wlan0 (16) wifi, validated, routes v4", at = 0)
        SupportNotes.add("started", at = 1000)
        val lines = SupportNotes.snapshot()
        assertEquals(2, lines.size)
        assertTrue(lines[0], Regex("""\d\d-\d\d \d\d:\d\d:\d\d network: wlan0 \(16\) wifi, validated, routes v4""").matches(lines[0]))
        assertEquals(listOf("network: wlan0 (16) wifi, validated, routes v4", "started"), texts())
    }

    @Test
    fun theStartOfTheSessionOutlivesAFlood() {
        SupportNotes.newSession()
        SupportNotes.add("network: none")
        repeat(SupportNotes.HEAD + SupportNotes.TAIL + 50) { SupportNotes.add("line $it") }
        val lines = texts()
        assertEquals("— start —", lines[0])
        assertEquals("network: none", lines[1])
        // The head, the count of what was dropped, the newest lines.
        assertEquals(SupportNotes.HEAD + 1 + SupportNotes.TAIL, lines.size)
        assertEquals("… 52 lines dropped …", SupportNotes.snapshot()[SupportNotes.HEAD])
        assertEquals("line ${SupportNotes.HEAD + SupportNotes.TAIL + 49}", lines.last())
    }

    @Test
    fun aNewStartKeepsTheEndOfTheOldSession() {
        SupportNotes.newSession()
        repeat(100) { SupportNotes.add("old $it") }
        SupportNotes.newSession()
        SupportNotes.add("new")
        val lines = texts()
        assertEquals(SupportNotes.PREVIOUS + 2, lines.size)
        assertEquals("old 99", lines[SupportNotes.PREVIOUS - 1])
        assertEquals(listOf("— start —", "new"), lines.takeLast(2))
    }

    @Test
    fun aLineIsOneLine() {
        SupportNotes.add("first\nsecond")
        assertEquals(listOf("first second"), texts())
    }
}
