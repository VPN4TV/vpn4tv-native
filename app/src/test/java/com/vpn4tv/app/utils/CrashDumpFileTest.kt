package com.vpn4tv.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashDumpFileTest {
    private fun file(text: String): File =
        File.createTempFile("go_crash", ".log").apply { deleteOnExit(); writeText(text) }

    @Test
    fun aSmallDumpIsReadWhole() {
        val text = "fatal error: found bad pointer in Go heap\ngoroutine 1 [running]:\n"
        assertEquals(text, CrashDumpFile.read(file(text), head = 64, tail = 64))
    }

    @Test
    fun aHugeDumpKeepsItsStartAndEnd() {
        val text = "fatal error: start\n" + "x".repeat(10_000) + "\nlast goroutine"
        val read = CrashDumpFile.read(file(text), head = 19, tail = 15)
        assertTrue(read.startsWith("fatal error: start\n"))
        assertTrue(read.endsWith("\nlast goroutine"))
        assertTrue(read.contains("… 10000 bytes skipped …"))
    }
}
