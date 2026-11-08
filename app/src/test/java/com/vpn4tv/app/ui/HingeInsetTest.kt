package com.vpn4tv.app.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Home on a foldable: its column goes into one pane, off the hinge (Adaptive.kt).
class HingeInsetTest {
    // A book-style foldable open, in window px: Home's body right of an 80 px rail
    // and under the app bar; the hinge a 20 px strip in the middle of the window.
    private val body = Rect(left = 80f, top = 200f, right = 2000f, bottom = 1700f)
    private val verticalHinge = Rect(left = 990f, top = 0f, right = 1010f, bottom = 1800f)

    @Test
    fun aVerticalHingeSendsTheColumnIntoTheWiderPane() {
        // Left pane 910 px (the rail takes some), right pane 990 px: the right one.
        assertEquals(HingeInset(left = 930f), hingeInset(body, verticalHinge, vertical = true))
    }

    @Test
    fun theLeftPaneWhenItIsTheWiderOne() {
        val rtlBody = Rect(left = 0f, top = 200f, right = 1920f, bottom = 1700f) // the rail on the right
        assertEquals(HingeInset(right = 930f), hingeInset(rtlBody, verticalHinge, vertical = true))
    }

    @Test
    fun equalPanesTakeTheRightOne() {
        val even = Rect(left = 0f, top = 0f, right = 2000f, bottom = 1700f)
        assertEquals(HingeInset(left = 1010f), hingeInset(even, verticalHinge, vertical = true))
    }

    @Test
    fun aHorizontalHingeSendsTheColumnIntoTheLowerPane() {
        // Tabletop, the hinge across the middle of an 1800 px window: the app bar
        // takes from the upper pane (690 px), the lower one is larger (790 px).
        val hinge = Rect(left = 0f, top = 890f, right = 2000f, bottom = 910f)
        assertEquals(HingeInset(top = 710f), hingeInset(body, hinge, vertical = false))
    }

    @Test
    fun theUpperPaneWhenItIsTheLargerOne() {
        val hinge = Rect(left = 0f, top = 1400f, right = 2000f, bottom = 1420f)
        assertEquals(HingeInset(bottom = 300f), hingeInset(body, hinge, vertical = false))
    }

    @Test
    fun aHingeOutsideTheBoxChangesNothing() {
        // The hinge beside a pane that does not reach it, above or below the box.
        val narrow = Rect(left = 80f, top = 200f, right = 900f, bottom = 1700f)
        assertTrue(hingeInset(narrow, verticalHinge, vertical = true).isNone)
        val above = Rect(left = 0f, top = 100f, right = 2000f, bottom = 120f)
        assertTrue(hingeInset(body, above, vertical = false).isNone)
        val touching = Rect(left = 2000f, top = 0f, right = 2020f, bottom = 1800f)
        assertTrue(hingeInset(body, touching, vertical = true).isNone)
    }
}
