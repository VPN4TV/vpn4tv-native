package com.vpn4tv.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Checkbox
import androidx.tv.material3.CheckboxDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.Text

/*
 * The TV screens' controls: Compose for TV (androidx.tv.material3), which
 * gives the focus, press and disabled states, the growth and a press on the
 * remote's OK as other TV apps have them (Google: "Use the TV version of APIs
 * wherever possible"). The look stays the app's: the white outline and the
 * growth of the focus mark (Modifier.tvFocus, now kept only for the focus
 * stops that are not controls), the colours of the dark TV theme. Phones keep
 * Material 3; nothing here is used there.
 */

/** The focus mark's outline, as Modifier.tvFocus draws it. */
private val FocusStroke = BorderStroke(3.dp, Color.White)

/** The same outline, dimmed, on a control that does not apply now (the TV keeps it focusable). */
private val DisabledFocusStroke = BorderStroke(3.dp, Color.White.copy(alpha = 0.4f))

/** The grey a focused row or card sits on: the plate the rows had always before. */
@Composable
private fun plate(): Color = MaterialTheme.colorScheme.surfaceVariant

/**
 * A button: [containerColor] stays as it is in focus (the white outline and
 * the growth mark it), as the Connect button always looked. At least
 * [minWidth] × [minHeight], or as wide as its place with [fillWidth].
 */
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    shape: Shape = CircleShape,
    focusedScale: Float = 1.1f,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    minWidth: Dp = Dp.Unspecified,
    minHeight: Dp = Dp.Unspecified,
    fillWidth: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = containerColor,
            focusedContentColor = contentColor,
            pressedContainerColor = containerColor,
            pressedContentColor = contentColor,
        ),
        scale = ButtonDefaults.scale(focusedScale = focusedScale),
        border = ButtonDefaults.border(focusedBorder = Border(FocusStroke)),
        contentPadding = PaddingValues(0.dp),
    ) {
        CenteredContent(contentPadding, minWidth, minHeight, fillWidth, content)
    }
}

/**
 * Compose for TV puts a button's content at its top start when the button is
 * larger than the content (a minimum size from outside, the full width): the
 * size is given here, inside, and the content centred in it.
 */
@Composable
private fun CenteredContent(
    padding: PaddingValues,
    minWidth: Dp,
    minHeight: Dp,
    fillWidth: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
            .padding(padding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A text button: the label in the theme's purple, no container (Material's TextButton, for the remote). */
@Composable
fun TvTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusedScale: Float = 1.1f,
    content: @Composable RowScope.() -> Unit,
) {
    TvButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        focusedScale = focusedScale,
        content = content,
    )
}

/** An outlined button: a thin grey outline, the white one in focus; as wide as its place with [fillWidth]. */
@Composable
fun TvOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusedScale: Float = 1.1f,
    fillWidth: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = OutlinedButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = colors.primary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = colors.primary,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = colors.primary,
        ),
        scale = OutlinedButtonDefaults.scale(focusedScale = focusedScale),
        border = OutlinedButtonDefaults.border(
            border = Border(BorderStroke(1.dp, colors.border)),
            focusedBorder = Border(FocusStroke),
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        CenteredContent(OutlinedButtonDefaults.ContentPadding, Dp.Unspecified, Dp.Unspecified, fillWidth, content)
    }
}

/** An icon button, [size] dp: the icon on nothing, the outline in focus. */
@Composable
fun TvIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    size: Dp = 48.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = IconButtonDefaults.shape(CircleShape),
        colors = IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = contentColor,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = contentColor,
        ),
        scale = IconButtonDefaults.scale(focusedScale = 1.1f),
        border = IconButtonDefaults.border(focusedBorder = Border(FocusStroke)),
        content = content,
    )
}

/**
 * A row of a list that is not a choice of one (a setting, a library's
 * licence): no container, as Google's TV lists have it ("List items are not
 * buttons. The items don't have containers."); in focus the grey plate, the
 * outline and a slight growth that keeps it inside the safe margin. One that
 * is not [enabled] stays focusable, dimmed, so TalkBack reads why.
 */
@Composable
fun TvRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = onSurface,
            focusedContainerColor = plate(),
            focusedContentColor = onSurface,
            pressedContainerColor = plate(),
            pressedContentColor = onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = onSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = TV_ROW_SCALE),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(FocusStroke),
            focusedDisabledBorder = Border(DisabledFocusStroke),
        ),
        content = content,
    )
}

/**
 * A row of a list with one chosen (a server, a key): Compose for TV's list
 * item, no container, the choice marked by [leadingContent] (Google: not by
 * the background alone); in focus as [TvRow].
 */
@Composable
fun TvListItem(
    selected: Boolean,
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = headlineContent,
        modifier = modifier,
        leadingContent = leadingContent,
        supportingContent = supportingContent,
        trailingContent = trailingContent,
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = onSurface,
            focusedContainerColor = plate(),
            focusedContentColor = onSurface,
            pressedContainerColor = plate(),
            pressedContentColor = onSurface,
            selectedContainerColor = Color.Transparent,
            selectedContentColor = onSurface,
            focusedSelectedContainerColor = plate(),
            focusedSelectedContentColor = onSurface,
            pressedSelectedContainerColor = plate(),
            pressedSelectedContentColor = onSurface,
        ),
        scale = ListItemDefaults.scale(focusedScale = TV_ROW_SCALE),
        border = ListItemDefaults.border(focusedBorder = Border(FocusStroke)),
    )
}

/** A card that opens something (the server on Home): the grey plate, the outline in focus. */
@Composable
fun TvCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.05f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = plate(),
            contentColor = onSurface,
            focusedContainerColor = plate(),
            focusedContentColor = onSurface,
        ),
        scale = CardDefaults.scale(focusedScale = focusedScale),
        border = CardDefaults.border(focusedBorder = Border(FocusStroke)),
        content = content,
    )
}

/**
 * A row that is read, not pressed (a library's licence): a focus stop, so the
 * remote scrolls the list and TalkBack reads the row whole; no container, the
 * plate and the focus mark in focus, as [TvRow].
 */
@Composable
fun TvReadRow(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    TvPanel(
        modifier = modifier.tvFocusMark(focused, shape, TV_ROW_SCALE)
            .focusable(interactionSource = interaction)
            .semantics(mergeDescendants = true) {},
        containerColor = if (focused) plate() else Color.Transparent,
        shape = shape,
        content = content,
    )
}

/** A panel that is only read (the add-key code, the proxy address): Compose for TV's plain surface. */
@Composable
fun TvPanel(
    modifier: Modifier = Modifier,
    containerColor: Color = plate(),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        colors = SurfaceDefaults.colors(containerColor = containerColor, contentColor = contentColor),
        content = content,
    )
}

/** A switch that only shows a row's state: the row is the control and tells TalkBack. */
@Composable
fun TvSwitch(checked: Boolean, enabled: Boolean = true) {
    Switch(checked = checked, onCheckedChange = null, enabled = enabled)
}

/** A check box that only shows a row's state, as [TvSwitch]. */
@Composable
fun TvCheckbox(checked: Boolean) {
    val colors = MaterialTheme.colorScheme
    Checkbox(
        checked = checked,
        onCheckedChange = null,
        colors = CheckboxDefaults.colors(
            checkedColor = colors.primary,
            uncheckedColor = colors.onSurfaceVariant,
            checkmarkColor = colors.onPrimary,
        ),
    )
}

/**
 * One of a few filters (the log's levels): outlined, the chosen one filled and
 * checked. Compose for TV's selectable surface (its chips are still
 * experimental); for TalkBack one of a set, as a radio button.
 */
@Composable
fun TvFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.semantics { role = Role.RadioButton },
        shape = SelectableSurfaceDefaults.shape(shape),
        colors = tvSegmentColors(),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.1f),
        border = tvSegmentBorder(shape),
    ) {
        Row(
            Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke()
            androidx.tv.material3.ProvideTextStyle(MaterialTheme.typography.labelMedium, content)
        }
    }
}

/**
 * A dialog over the screen (the TV layout guide's center overlay): a title, a
 * text, the buttons at the end. [content] lays out the buttons; the caller
 * puts the focus on the one that helps.
 */
@Composable
fun TvDialog(
    onDismissRequest: () -> Unit,
    title: String?,
    text: (@Composable () -> Unit)?,
    buttons: @Composable RowScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        TvPanel(
            // TalkBack says the dialog's title as it opens, as Material's dialogs say "Dialog".
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp)
                .then(if (title != null) Modifier.semantics { paneTitle = title } else Modifier),
            containerColor = plate(),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(Modifier.padding(24.dp)) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(16.dp))
                }
                if (text != null) {
                    androidx.tv.material3.ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.tv.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                            content = text,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

/** One of a set, chosen: the filters of the log, the modes of per-app proxy. Filled when chosen, no change in focus. */
@Composable
internal fun tvSegmentColors() = SelectableSurfaceDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = Color.Transparent,
    focusedContentColor = MaterialTheme.colorScheme.onSurface,
    pressedContainerColor = Color.Transparent,
    pressedContentColor = MaterialTheme.colorScheme.onSurface,
    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    focusedSelectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    focusedSelectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    pressedSelectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    pressedSelectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

/** The outline of one of a set ([tvSegmentColors]): thin grey, white in focus. */
@Composable
internal fun tvSegmentBorder(shape: Shape) = SelectableSurfaceDefaults.border(
    border = Border(BorderStroke(1.dp, MaterialTheme.colorScheme.border), shape = shape),
    focusedBorder = Border(FocusStroke, shape = shape),
    selectedBorder = Border(BorderStroke(1.dp, MaterialTheme.colorScheme.border), shape = shape),
    focusedSelectedBorder = Border(FocusStroke, shape = shape),
)
