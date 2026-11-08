package com.vpn4tv.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The QR codes are drawn 1:1 in the screen's pixels (QrImage): they are sharp
 * only if every module is a whole number of pixels, with the white quiet zone
 * around the code.
 */
class QrPixelsTest {
    private val botLink = "https://telegram.me/VPN4TV_Bot?start=0f8fad5b-d9cb-469f-a165-70867728950e"
    private val lanLink = "http://192.168.11.77:8384/"

    // The sizes the screens ask for (phone 180 dp, TV 200 dp, LAN import 240 dp)
    // at the densities of phones and of TVs (1080p xhdpi, 4K xxxhdpi), as roundToPx gives them.
    private val sizes = listOf(180, 200, 240).flatMap { dp ->
        listOf(1.0f, 1.5f, 2.0f, 2.625f, 3.0f, 3.5f, 4.0f).map { (dp * it).roundToInt() }
    }

    @Test
    fun modulesAreWholePixelsInAWhiteQuietZone() {
        for (link in listOf(botLink, lanLink)) for (size in sizes) {
            val px = qrPixels(link, size)
            assertNotNull("$link at $size px", px)
            px!!
            assertEquals(size * size, px.size)
            assertTrue(px.all { it == QR_DARK || it == QR_LIGHT })
            // The code starts with the top-left finder pattern: its first row is 7 dark modules.
            val first = (0 until size).first { px[it * size + it] == QR_DARK }
            var run = 0
            while (px[first * size + first + run] == QR_DARK) run++
            assertEquals("$link at $size px: the finder's first row", 0, run % 7)
            val m = run / 7
            assertTrue("$link at $size px: modules of $m px", m >= 2)
            assertTrue("$link at $size px: the quiet zone", first >= 4 * m)
            // Every module is an m x m square of one colour.
            val modules = (size - 2 * first) / m
            for (my in 0 until modules) for (mx in 0 until modules) {
                val x0 = first + mx * m
                val y0 = first + my * m
                val c = px[y0 * size + x0]
                for (dy in 0 until m) for (dx in 0 until m) {
                    assertEquals("$link at $size px, module $mx,$my", c, px[(y0 + dy) * size + x0 + dx])
                }
            }
        }
    }

    @Test
    fun tooSmallForTheCodeGivesNothing() {
        assertNull(qrPixels(botLink, 20))
    }
}
