package com.vpn4tv.app.ui

import android.content.pm.PackageManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.Application
import com.vpn4tv.app.R

/**
 * TV or phone. The two follow different Google guides: Android TV design
 * (10-foot UI, D-pad focus, overscan-safe margins) and Material 3 for phones
 * (top app bar, navigation bar, edge-to-edge, light and dark theme).
 */
object Device {
    val isTv: Boolean by lazy {
        Application.application.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}

/** Android TV layout guide: a 5 % overscan-safe margin, 48 dp at the sides and 27 dp at the top and bottom. */
object TvSafeArea {
    val horizontal: Dp = 48.dp
    val vertical: Dp = 27.dp
}

/**
 * The focus mark on TV: the control grows (1.1x for buttons, as in the TV
 * buttons guide; less for full-width rows, which would otherwise run into the
 * safe margin) and gets a white outline. One scheme on every screen, as the TV
 * navigation guide asks. The controls are Compose for TV's (TvComponents),
 * which draw the same mark from their own focus state; this modifier is for
 * the focus stops that are not controls (a code to read, a licence row).
 * Does nothing on a phone.
 */
@Composable
fun Modifier.tvFocus(
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.1f,
): Modifier {
    if (!Device.isTv) return this
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.hasFocus }
        .tvFocusMark(focused, shape, focusedScale)
}

/**
 * The same mark, drawn for a focus held elsewhere: a group that TalkBack reads
 * as one (the add-key code with its instructions) marks only the part the eye
 * looks for. Does nothing on a phone.
 */
@Composable
fun Modifier.tvFocusMark(
    focused: Boolean,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.1f,
): Modifier {
    if (!Device.isTv) return this
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "tvFocusScale")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .border(3.dp, if (focused) Color.White else Color.Transparent, shape)
}

/** For full-width rows and cards: a smaller growth keeps them inside the safe margin. */
const val TV_ROW_SCALE = 1.03f

/**
 * One screen below Home: on a phone a Material 3 top app bar with Back that
 * stays put and tints on scroll, content padded for the system bars; on TV
 * the title row inside the overscan-safe area, no on-screen Back (the remote
 * has one, and the TV guide does not ask for it).
 *
 * [collapsingTopBar], for long screens: the phone's bar scrolls away with the
 * content and comes back on the first scroll up (Google's edge-to-edge guide:
 * "Top app bars should collapse while scrolling"), more rows on a small screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    collapsingTopBar: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (Device.isTv) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = TvSafeArea.horizontal, vertical = TvSafeArea.vertical),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.tv.material3.Text(
                    title,
                    style = androidx.tv.material3.MaterialTheme.typography.headlineMedium,
                    color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
            Box(Modifier.weight(1f)) { content(PaddingValues(0.dp)) }
        }
        return
    }
    val scroll = if (collapsingTopBar) TopAppBarDefaults.enterAlwaysScrollBehavior()
        else TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
                actions = actions,
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = floatingActionButton,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding -> content(padding) }
}

/** Phone list content: Material 3 compact margin at the sides, the scaffold's insets top and bottom. */
fun PaddingValues.phoneList(extraBottom: Dp = 16.dp): PaddingValues = object : PaddingValues by this {
    override fun calculateBottomPadding(): Dp = this@phoneList.calculateBottomPadding() + extraBottom
}

@Composable
fun HSpacer(width: Dp) = Spacer(Modifier.width(width))

/** Bytes for people: КБ / МБ / ГБ in Russian, from the string resources. */
@Composable
fun formatBytesLocalized(bytes: Long): String {
    val units = listOf(
        stringResource(R.string.unit_bytes), stringResource(R.string.unit_kb),
        stringResource(R.string.unit_mb), stringResource(R.string.unit_gb),
    )
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    val number = if (unit == 0) value.toLong().toString() else "%.1f".format(value)
    return "$number ${units[unit]}"
}
