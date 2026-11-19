package com.vpn4tv.app.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * What the app tells the user while it is open. A phone shows it as a
 * snackbar, with the way out as its action (Google: "If your app is in the
 * foreground, consider using a snackbar instead of using a toast"; a toast
 * is cut to two lines on Android 12+). A TV shows the VPN errors as a dialog
 * with the next step on a button, so a long instruction no longer disappears
 * before it is read across the room; the rest stays a toast there.
 */
enum class AppMessage(@StringRes val text: Int, @StringRes val action: Int? = null, val long: Boolean = false) {
    VpnDenied(R.string.error_vpn_denied, R.string.action_retry),
    NoVpnDialog(R.string.error_no_vpn_dialog, R.string.action_open_settings, long = true),
    NoKey(R.string.error_no_subscription, R.string.action_add),
    KeyAdded(R.string.profile_imported),
    RefreshingExpiredKey(R.string.refreshing_expired_subscription),
    ImportTooLarge(R.string.error_import_too_large, long = true),
    CodeCopied(R.string.code_copied),

    /** Phone, once: the home-screen widget, with Add (QuickOffers). */
    WidgetOffer(R.string.widget_offer, R.string.action_add),

    /** Android ended the app while the VPN ran: the app's restart after a kill, on a phone and a TV (VpnEndedOffer). */
    RestartOffer(R.string.vpn_ended_restart_text, R.string.action_turn_on),

    /** TCL's own guard ended the VPN: the setting in TCL's security app, and the restart after a kill (VpnEndedOffer). */
    TclGuardOffer(R.string.vpn_ended_tcl_text, R.string.action_turn_on),

    /** The same with the restart on, which the guard kept from bringing the VPN back: the setting alone, one button. */
    TclGuardNotice(R.string.vpn_ended_tcl_restart_on_text, R.string.ok),
}

/** The offers after Android ended the VPN: a dialog on the phone too (owner 2026-11-17: «модалкой»). */
private val AppMessage.isVpnEndedOffer: Boolean
    get() = this == AppMessage.RestartOffer || this == AppMessage.TclGuardOffer || this == AppMessage.TclGuardNotice

/** A dialog with nothing to decide: one button, no "Not now". */
private val AppMessage.onlyInforms: Boolean
    get() = this == AppMessage.TclGuardNotice

object AppMessages {
    // Buffered: a message posted before the screen collects (an import at
    // launch) waits for it instead of being lost. From any thread.
    private val channel = Channel<AppMessage>(capacity = 8)
    internal val flow = channel.receiveAsFlow()

    fun post(message: AppMessage) {
        channel.trySend(message)
    }
}

/** Phone: messages as snackbars in the app's scaffold; an action does what its label says. */
@Composable
fun PhoneMessages(
    snackbar: SnackbarHostState,
    onRetryConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddKey: () -> Unit,
) {
    val context = LocalContext.current
    val retry by rememberUpdatedState(onRetryConnect)
    val settings by rememberUpdatedState(onOpenSettings)
    val add by rememberUpdatedState(onAddKey)
    var dialog by remember { mutableStateOf<AppMessage?>(null) }
    LaunchedEffect(snackbar) {
        AppMessages.flow.collect { message ->
            if (message.isVpnEndedOffer) {
                dialog = message
                return@collect
            }
            // One at a time, in order: the host queues them.
            launch {
                val result = snackbar.showSnackbar(
                    message = context.getString(message.text),
                    actionLabel = message.action?.let { context.getString(it) },
                    duration = if (message.action != null || message.long) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    when (message) {
                        AppMessage.VpnDenied -> retry()
                        AppMessage.NoVpnDialog -> settings()
                        AppMessage.NoKey -> add()
                        AppMessage.WidgetOffer -> QuickOffers.pinWidget(context)
                        else -> {}
                    }
                }
            }
        }
    }
    dialog?.let { message ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.vpn_ended_title)) },
            text = { Text(stringResource(message.text)) },
            confirmButton = {
                TextButton(onClick = { dialog = null; VpnEndedOffer.accept(message) }) {
                    Text(stringResource(message.action ?: R.string.action_continue))
                }
            },
            dismissButton = if (message.onlyInforms) null else {
                { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_not_now)) } }
            },
        )
    }
}

/**
 * TV: no VPN permission, or no system window to ask for it, opens a dialog
 * over the screen (the TV layout guide's center overlay, for "urgent
 * information or prompt a decision"); the remote lands on the button that
 * fixes it. Other messages stay toasts.
 */
@Composable
fun TvMessages(onRetryConnect: () -> Unit, onProxyMode: () -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<AppMessage?>(null) }
    LaunchedEffect(Unit) {
        AppMessages.flow.collect { message ->
            when (message) {
                AppMessage.VpnDenied, AppMessage.NoVpnDialog, AppMessage.RestartOffer,
                AppMessage.TclGuardOffer, AppMessage.TclGuardNotice -> dialog = message
                else -> Toast.makeText(
                    context, message.text, if (message.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    when (dialog) {
        AppMessage.VpnDenied -> TvAlert(
            title = R.string.error_vpn_denied,
            text = R.string.vpn_denied_tv_text,
            confirm = R.string.action_try_again,
            onConfirm = { dialog = null; onRetryConnect() },
            onDismiss = { dialog = null },
        )
        AppMessage.NoVpnDialog -> TvAlert(
            title = R.string.no_vpn_dialog_title,
            text = R.string.no_vpn_dialog_text,
            confirm = R.string.action_enable_proxy_mode,
            onConfirm = { dialog = null; onProxyMode() },
            onDismiss = { dialog = null },
        )
        AppMessage.RestartOffer, AppMessage.TclGuardOffer, AppMessage.TclGuardNotice -> {
            val message = dialog!!
            TvAlert(
                title = R.string.vpn_ended_title,
                text = message.text,
                confirm = message.action ?: R.string.action_continue,
                dismiss = if (message.onlyInforms) null else R.string.action_not_now,
                onConfirm = { dialog = null; VpnEndedOffer.accept(message) },
                onDismiss = { dialog = null },
            )
        }
        else -> {}
    }
}

@Composable
private fun TvAlert(
    @StringRes title: Int,
    @StringRes text: Int,
    @StringRes confirm: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Null: the confirm button alone (BACK still closes the dialog). */
    @StringRes dismiss: Int? = R.string.cancel,
) {
    val confirmFocus = remember { FocusRequester() }
    // The fix in focus from the start; a request made while the dialog's
    // window is still coming up is dropped, so ask a few times.
    LaunchedEffect(Unit) {
        repeat(5) {
            delay(100)
            if (runCatching { confirmFocus.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    TvDialog(
        onDismissRequest = onDismiss,
        title = stringResource(title),
        text = { androidx.tv.material3.Text(stringResource(text)) },
    ) {
        if (dismiss != null) {
            TvTextButton(onClick = onDismiss) {
                androidx.tv.material3.Text(stringResource(dismiss))
            }
        }
        TvTextButton(onClick = onConfirm, modifier = Modifier.focusRequester(confirmFocus)) {
            androidx.tv.material3.Text(stringResource(confirm))
        }
    }
}
