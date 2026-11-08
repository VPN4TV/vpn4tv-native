package com.vpn4tv.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phones, tablets and foldables (Google's large-screen guidance, the core
 * quality rule Line_Length: "set a max width on content and components to
 * prevent stretching full width"). A phone is narrower than all of these, so
 * nothing changes there; the TV keeps its own layouts.
 */
object ContentWidth {
    /** Lists of keys, servers and apps. */
    val list: Dp = 840.dp

    /** Settings and other text: lines of 45–75 characters. */
    val text: Dp = 640.dp

    /** A primary button, such as Connect. */
    val button: Dp = 400.dp
}

/** At most [max] wide and centred in a wider window; the TV is left as it is. */
fun Modifier.readableWidth(max: Dp): Modifier =
    if (Device.isTv) this
    else this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

/** How far content keeps from each side of its box, in px, to stay off a foldable's hinge. */
internal data class HingeInset(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    val isNone: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f
}

/**
 * Where a [hinge] crosses a [box] (both in window px): the inset that moves the
 * box's content into one pane, the larger one (on a tie, the right or the
 * lower). A vertical hinge, a phone open like a book, splits left and right; a
 * horizontal one, the tabletop posture, top and bottom. None when it misses.
 */
internal fun hingeInset(box: Rect, hinge: Rect, vertical: Boolean): HingeInset {
    if (vertical) {
        if (hinge.right <= box.left || hinge.left >= box.right) return HingeInset()
        val leftPane = hinge.left - box.left
        val rightPane = box.right - hinge.right
        return if (rightPane >= leftPane) HingeInset(left = hinge.right - box.left)
        else HingeInset(right = box.right - hinge.left)
    }
    if (hinge.bottom <= box.top || hinge.top >= box.bottom) return HingeInset()
    val topPane = hinge.top - box.top
    val bottomPane = box.bottom - hinge.bottom
    return if (bottomPane >= topPane) HingeInset(top = hinge.bottom - box.top)
    else HingeInset(bottom = box.bottom - hinge.top)
}

/**
 * A foldable open like a book or standing as a tent: the content of this box
 * moves into one pane, off the hinge (Google's foldable postures guide: "consider
 * whether controls and text should avoid the hinge space"). Only a hinge that
 * separates the screen or hides part of it counts; a flat fold, a phone or a
 * tablet change nothing. Not used on TV.
 */
@Composable
fun Modifier.offHinge(): Modifier {
    if (Device.isTv) return this
    val hinges = currentWindowAdaptiveInfo().windowPosture.hingeList.filter { it.isSeparating || it.isOccluding }
    var box by remember { mutableStateOf<Rect?>(null) }
    val inset = box?.let { b ->
        hinges.asSequence().map { hingeInset(b, it.bounds, it.isVertical) }.firstOrNull { !it.isNone }
    } ?: HingeInset()
    val padding = with(LocalDensity.current) {
        PaddingValues.Absolute(inset.left.toDp(), inset.top.toDp(), inset.right.toDp(), inset.bottom.toDp())
    }
    return this.onGloballyPositioned { box = it.boundsInWindow() }.padding(padding)
}
