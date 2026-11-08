package com.vpn4tv.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vpn4tv.app.R
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.bg.ServiceNotification
import com.vpn4tv.app.database.Settings as AppSettings

/**
 * Phone, while the app is on screen: a start that ends is felt (a confirming
 * tick when connected, a rejecting one when it failed; Android's touch
 * feedback setting applies), and the first connect is the moment to ask for
 * notifications (Android 13+ starts with them off; Google: ask in context).
 * Asked once: after "Not now" or a refusal, Settings → Notifications brings
 * them back.
 *
 * At most one question per connect, the earliest due first, the rest wait
 * for a later connect: notifications, then the Quick Settings tile, the
 * Google Play rating, the widget (QuickOffers, ReviewPrompt). Only while the
 * app's window has the focus: not for a connect from the Quick Settings tile
 * with the app open under the shade, nor over a dialog.
 */
@Composable
fun ConnectFeedback() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var askNotifications by rememberSaveable { mutableStateOf(false) }
    // Here, not in the dialog: the answer comes after the dialog has gone.
    val requestNotifications = rememberNotificationPermissionRequest()
    LaunchedEffect(lifecycleOwner) {
        // Only while the app is in front: no buzz for a start that ends behind another app.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            BoxService.startOutcomes.collect { outcome ->
                when (outcome) {
                    BoxService.StartOutcome.Connected -> {
                        haptics.performHapticFeedback(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackType.Confirm
                            else HapticFeedbackType.LongPress,
                        )
                        val activity = context.findActivity()
                        when {
                            // A connect from the tile while the app is open
                            // under the shade: no question then, a later
                            // connect asks it.
                            activity == null || !activity.hasWindowFocus() -> {}
                            shouldOfferNotifications(context) -> {
                                AppSettings.notificationPromptShown = true
                                askNotifications = true
                            }
                            QuickOffers.tileDue() -> QuickOffers.requestTile(activity)
                            ReviewPrompt.due(context) -> ReviewPrompt.launch(activity)
                            QuickOffers.widgetDue(context) -> {
                                AppSettings.widgetOffered = true
                                AppMessages.post(AppMessage.WidgetOffer)
                            }
                        }
                    }
                    BoxService.StartOutcome.Failed -> haptics.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackType.Reject
                        else HapticFeedbackType.LongPress,
                    )
                }
            }
        }
    }
    if (askNotifications) {
        AlertDialog(
            onDismissRequest = { askNotifications = false },
            text = { Text(stringResource(R.string.notification_prompt_text)) },
            confirmButton = {
                TextButton(onClick = { askNotifications = false; requestNotifications() }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { askNotifications = false }) {
                    Text(stringResource(R.string.action_not_now))
                }
            },
        )
    }
}

/**
 * TV, while the app is on screen: Google Play's rating card after a connect,
 * when due (ReviewPrompt; Google TV only, the TVs the API supports).
 */
@Composable
fun TvConnectFeedback() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            BoxService.startOutcomes.collect { outcome ->
                val activity = context.findActivity()
                if (outcome == BoxService.StartOutcome.Connected && activity != null &&
                    activity.hasWindowFocus() && ReviewPrompt.due(context)
                ) {
                    ReviewPrompt.launch(activity)
                }
            }
        }
    }
}

/** Android 13+, the permission not given yet, and the question not asked before. */
private fun shouldOfferNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !AppSettings.notificationPromptShown &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** Whether the app's notifications can show: the permission (Android 13+) and Android's switch for the app. */
fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/**
 * Turns notifications on the way Android still allows: its permission dialog
 * while it may show (Android 13+), otherwise the app's notification settings.
 * Granted: the running VPN's notification appears at once.
 */
@Composable
fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ServiceNotification.refresh()
        onResult(granted)
    }
    return remember(context, launcher) {
        {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
            // Asked before and no rationale due: refused for good, Android
            // would not show its dialog again.
            val refusedForGood = AppSettings.notificationPermissionRequested &&
                context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } == false
            if (needsPermission && !refusedForGood) {
                AppSettings.notificationPermissionRequested = true
                launcher.launch(permission)
            } else {
                openAppNotificationSettings(context)
            }
        }
    }
}

/** Android's notification settings of this app (its app info page before Android 8). */
fun openAppNotificationSettings(context: Context) {
    val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        null
    }
    val appInfo = Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    startFirst(context, listOfNotNull(notifications, appInfo))
}

/** Android's VPN settings: always-on VPN and blocking connections without it are there. */
fun openVpnSettings(context: Context) {
    startFirst(
        context,
        listOf(
            Intent(android.provider.Settings.ACTION_VPN_SETTINGS),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        ),
    )
}

/** Some firmware lacks a settings page: the next, broader one opens instead. */
private fun startFirst(context: Context, intents: List<Intent>) {
    for (intent in intents) {
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            Log.w("Settings", "no ${intent.action}: ${e.message}")
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
