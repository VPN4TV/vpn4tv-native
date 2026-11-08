package com.vpn4tv.app.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.bg.QuickToggle
import com.vpn4tv.app.bg.ServiceNotification
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.ui.AppShortcuts
import com.vpn4tv.app.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * The home-screen widget (phones and tablets): the VPN's state, the key it
 * connects with and a button that connects or disconnects; a tap elsewhere
 * opens the app. 2×1 by default, down to 1×1 (the button alone), wider for
 * longer key names. Colours from the system's theme (wallpaper colours on
 * Android 12+, light and dark).
 *
 * The button connects without opening the app when nothing has to be asked:
 * the VPN permission is there and the key is fresh. Otherwise it opens the
 * app's own connect, which asks what it has to (Android's VPN permission
 * dialog, a refresh of an expired key, a key to add).
 */
class VpnWidget : GlanceAppWidget() {
    companion object {
        private const val TAG = "VpnWidget"

        // 1x1: the button. 2x1 on a dense grid (narrow), on most phones (wide),
        // 3x1 and up (wider): the state's type grows with the room, so that
        // "Disconnected" fits beside the button. Tall: room for the key's name.
        private val SMALL = DpSize(48.dp, 48.dp)
        private val SMALL_TALL = DpSize(48.dp, 84.dp)
        private val NARROW = DpSize(110.dp, 48.dp)
        private val NARROW_TALL = DpSize(110.dp, 72.dp)
        private val WIDE = DpSize(160.dp, 48.dp)
        private val WIDE_TALL = DpSize(160.dp, 72.dp)
        private val WIDER = DpSize(200.dp, 48.dp)
        private val WIDER_TALL = DpSize(200.dp, 72.dp)

        // Two rows and more: the server, the key and the session's traffic.
        private val NARROW_ROWS = DpSize(110.dp, 110.dp)
        private val WIDE_ROWS = DpSize(160.dp, 110.dp)
        private val WIDER_ROWS = DpSize(200.dp, 110.dp)

        /** Read in the composition, so a widget whose session still runs follows it at once. */
        private val model = MutableStateFlow<WidgetModel?>(null)

        /** The VPN or the key changed: placed widgets are drawn anew. */
        suspend fun refresh(context: Context) {
            val ids = try {
                GlanceAppWidgetManager(context).getGlanceIds(VpnWidget::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "widgets not listed: ${e.message}")
                return
            }
            if (ids.isEmpty()) return
            model.value = WidgetModel.load(context)
            VpnWidget().updateAll(context)
        }
    }

    override val sizeMode = SizeMode.Responsive(
        setOf(SMALL, SMALL_TALL, NARROW, NARROW_TALL, WIDE, WIDE_TALL, WIDER, WIDER_TALL, NARROW_ROWS, WIDE_ROWS, WIDER_ROWS),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetModel.load(context)
        model.value = initial
        provideContent {
            val current by model.collectAsState()
            GlanceTheme { Content(context, current ?: initial) }
        }
    }

    @Composable
    private fun Content(context: Context, m: WidgetModel) {
        val size = LocalSize.current
        val openApp = actionStartActivity(
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER),
        )
        val button = WidgetButton.of(context, m)
        var background = GlanceModifier.fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .appWidgetBackground()
            .clickable(openApp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            background = background.cornerRadius(android.R.dimen.system_app_widget_background_radius)
        }
        if (size.width < NARROW.width) {
            // 1×1: the button, and the name under it where the cell is tall enough.
            Column(
                background.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                button.Draw()
                if (size.height >= SMALL_TALL.height) {
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        context.getString(R.string.widget_name), maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    )
                }
            }
            return
        }
        val type = when {
            size.width >= WIDER.width -> WidgetType(16.dp, 12.sp, 16.sp)
            size.width >= WIDE.width -> WidgetType(12.dp, 11.sp, 14.sp)
            else -> WidgetType(10.dp, 11.sp, 12.sp)
        }
        Row(
            background.padding(start = type.start, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    context.getString(R.string.widget_name), maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = type.small),
                )
                Text(
                    context.getString(stateText(m)), maxLines = 1,
                    style = TextStyle(color = stateColor(m), fontSize = type.state, fontWeight = FontWeight.Bold),
                )
                val small = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = type.small)
                if (size.height >= NARROW_ROWS.height) {
                    // Two rows and more: the server, the key and the session's traffic.
                    listOfNotNull(m.server, m.keyName, m.traffic).forEach { Text(it, maxLines = 1, style = small) }
                } else {
                    // Connected: the server, as the tile and the notification show it;
                    // otherwise the key it connects with (no key: the state line says so).
                    val detail = m.server ?: m.keyName
                    if (size.height >= NARROW_TALL.height && detail != null) Text(detail, maxLines = 1, style = small)
                }
            }
            Spacer(GlanceModifier.width(6.dp))
            button.Draw()
        }
    }

    private fun stateText(m: WidgetModel): Int = when {
        m.keyName == null && m.status == Status.Stopped -> R.string.widget_no_key
        m.status == Status.Started -> R.string.status_connected
        m.status == Status.Starting -> R.string.status_connecting
        m.status == Status.Stopping -> R.string.status_disconnecting
        else -> R.string.status_disconnected
    }

    // The app's state colours (Home's status line), for the light and the dark theme.
    @Composable
    private fun stateColor(m: WidgetModel): ColorProvider = when (m.status) {
        Status.Started -> ColorProvider(day = Color(0xFF1B7F3A), night = Color(0xFF5BD47A))
        Status.Starting, Status.Stopping -> ColorProvider(day = Color(0xFF8A6100), night = Color(0xFFFFD54F))
        Status.Stopped -> GlanceTheme.colors.onSurface
    }
}

/** The widget's start padding and type sizes for its width. */
private data class WidgetType(val start: Dp, val small: TextUnit, val state: TextUnit)

/** What the widget shows. */
data class WidgetModel(
    val status: Status,
    /** Null: no key yet, the button adds one. */
    val keyName: String?,
    val alwaysOn: Boolean,
    /** A tap on Connect starts the VPN without the app (the permission is there, the key is fresh). */
    val directConnect: Boolean,
    /** The server while connected, when the app follows it (the same source as the tile). */
    val server: String? = null,
    /** The session's traffic while connected, "↑ … · ↓ …", under the same setting as [server]. */
    val traffic: String? = null,
) {
    companion object {
        suspend fun load(context: Context): WidgetModel = withContext(Dispatchers.IO) {
            // Drawn on every change, also in the background: never ask Android
            // here (see QuickToggle.facts); a tap weighs the real state.
            val facts = QuickToggle.facts(context, askAndroid = false)
            val id = Settings.selectedProfile
            val name = if (id != -1L) ProfileManager.get(id)?.name else null
            WidgetModel(
                status = facts.status,
                // A key with an empty name still is a key.
                keyName = name?.ifBlank { context.getString(R.string.widget_name) },
                alwaysOn = facts.alwaysOn,
                directConnect = QuickToggle.decide(
                    QuickToggle.Offer.Connect, facts.copy(status = Status.Stopped),
                ) == QuickToggle.Step.Start,
                server = ServiceNotification.currentServer.value
                    ?.takeIf { facts.status == Status.Started && it.isNotBlank() },
                traffic = ServiceNotification.currentTraffic.value
                    ?.takeIf { facts.status == Status.Started && it.size == 2 }
                    ?.let { "↑ ${bytes(context, it[0])} · ↓ ${bytes(context, it[1])}" },
            )
        }

        /** Bytes for people in the app's units (Б / КБ / МБ / ГБ in Russian). */
        private fun bytes(context: Context, count: Long): String {
            val units = listOf(R.string.unit_bytes, R.string.unit_kb, R.string.unit_mb, R.string.unit_gb)
            var value = count.toDouble()
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024
                unit++
            }
            val number = if (unit == 0 || value >= 100) "%.0f".format(value) else "%.1f".format(value)
            return "$number ${context.getString(units[unit])}"
        }
    }
}

/** The round button: its icon, words for TalkBack, colours and what a tap does. */
private class WidgetButton(
    val icon: Int,
    val description: String,
    val action: Action?,
    val busy: Boolean,
    val on: Boolean,
) {
    @Composable
    fun Draw() {
        val colors = GlanceTheme.colors
        CircleIconButton(
            imageProvider = ImageProvider(icon),
            contentDescription = description,
            // Between two states the button keeps its place and takes no tap.
            onClick = action ?: noAction(),
            enabled = action != null,
            backgroundColor = when {
                on -> colors.primary
                busy -> colors.surfaceVariant
                else -> colors.secondaryContainer
            },
            contentColor = when {
                on -> colors.onPrimary
                busy -> colors.onSurfaceVariant
                else -> colors.onSecondaryContainer
            },
        )
    }

    companion object {
        fun of(context: Context, m: WidgetModel): WidgetButton {
            val connect = context.getString(R.string.action_connect)
            val disconnect = context.getString(R.string.action_disconnect)
            return when {
                m.status == Status.Started -> WidgetButton(
                    R.drawable.ic_widget_power, disconnect,
                    // Always-on VPN: Android turns it off; the app says so on Home.
                    if (m.alwaysOn) actionStartActivity(AppShortcuts.intent(context, AppShortcuts.ACTION_DISCONNECT))
                    else toggle(QuickToggle.Offer.Disconnect),
                    busy = false, on = true,
                )
                m.status == Status.Starting || m.status == Status.Stopping -> WidgetButton(
                    R.drawable.ic_widget_power, context.getString(
                        if (m.status == Status.Starting) R.string.status_connecting else R.string.status_disconnecting,
                    ),
                    null, busy = true, on = m.status == Status.Starting,
                )
                m.keyName == null -> WidgetButton(
                    R.drawable.ic_widget_add, context.getString(R.string.title_add_subscription),
                    actionStartActivity(AppShortcuts.intent(context, AppShortcuts.ACTION_ADD_KEY)),
                    busy = false, on = false,
                )
                m.directConnect -> WidgetButton(
                    R.drawable.ic_widget_power, connect, toggle(QuickToggle.Offer.Connect), busy = false, on = false,
                )
                else -> WidgetButton(
                    R.drawable.ic_widget_power, connect,
                    actionStartActivity(AppShortcuts.intent(context, AppShortcuts.ACTION_CONNECT)),
                    busy = false, on = false,
                )
            }
        }

        private fun toggle(offer: QuickToggle.Offer): Action =
            actionRunCallback<WidgetToggleAction>(actionParametersOf(WidgetToggleAction.OFFER to offer.name))

        /** Only draws the widget anew (no offer): for a button that takes no tap anyway. */
        private fun noAction(): Action = actionRunCallback<WidgetToggleAction>()
    }
}

/**
 * A tap on the widget's button, without opening the app. Weighed against
 * the VPN's real state (QuickToggle): a widget left showing an old state
 * is only drawn anew.
 */
class WidgetToggleAction : ActionCallback {
    companion object {
        val OFFER = ActionParameters.Key<String>("offer")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val offer = parameters[OFFER]?.let { runCatching { QuickToggle.Offer.valueOf(it) }.getOrNull() }
        if (offer != null) {
            when (QuickToggle.decide(offer, QuickToggle.facts(context))) {
                // A widget tap lets the app start its service from the background.
                QuickToggle.Step.Start -> if (!BoxService.start()) openApp(context)
                QuickToggle.Step.Stop -> BoxService.stop()
                QuickToggle.Step.OpenApp -> openApp(context)
                QuickToggle.Step.Redraw -> {}
            }
        }
        VpnWidget.refresh(context)
    }

    private fun openApp(context: Context) {
        try {
            context.startActivity(AppShortcuts.intent(context, AppShortcuts.ACTION_CONNECT))
        } catch (e: Exception) {
            Log.w("VpnWidget", "app not opened from the widget: ${e.message}")
        }
    }
}

class VpnWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VpnWidget()
}
