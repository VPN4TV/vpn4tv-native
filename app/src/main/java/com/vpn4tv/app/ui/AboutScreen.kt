package com.vpn4tv.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.BuildConfig
import com.vpn4tv.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText

/** [onBack] null: shown beside the settings list on a wide window, no Back arrow. */
@Composable
fun AboutScreen(onBack: (() -> Unit)?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Phone only: a TV opens no browser and no Telegram link (Google's TV-WB),
    // it shows the link as a QR code for the phone instead.
    fun openUrl(url: String) {
        val full = if (url.startsWith("http")) url else "https://$url"
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }
    var showLicenses by remember { mutableStateOf(false) }
    // TV: the privacy policy or the Telegram channel as a QR code for the
    // phone; Back returns to the button that opened it, the licenses' too.
    var shownLink by remember { mutableStateOf<TvLink?>(null) }
    var refocusLink by remember { mutableStateOf<TvLink?>(null) }
    val linkFocus = remember { TvLink.entries.associateWith { FocusRequester() } }
    val privacyUrl = stringResource(R.string.about_privacy_url)
    val colors = MaterialTheme.colorScheme

    when (shownLink) {
        TvLink.Privacy -> {
            androidx.activity.compose.BackHandler { shownLink = null; refocusLink = TvLink.Privacy }
            LinkQr(
                title = stringResource(R.string.about_privacy),
                url = privacyUrl,
                label = privacyUrl.substringAfter("://").substringBefore('#'),
                hint = stringResource(R.string.about_privacy_on_phone),
                qrDescription = stringResource(R.string.about_privacy_qr),
            )
            return
        }
        TvLink.Telegram -> {
            androidx.activity.compose.BackHandler { shownLink = null; refocusLink = TvLink.Telegram }
            LinkQr(
                title = stringResource(R.string.about_telegram_channel),
                url = TELEGRAM_CHANNEL_URL,
                label = "@VPN4TV",
                hint = stringResource(R.string.about_telegram_on_phone),
                qrDescription = stringResource(R.string.about_telegram_qr),
            )
            return
        }
        // The licenses open as a list over the page (showLicenses), not a QR.
        TvLink.Licenses, null -> {}
    }
    LaunchedEffect(refocusLink) {
        val link = refocusLink ?: return@LaunchedEffect
        // Asked again for a moment: before the button is placed the request fails.
        repeat(10) {
            if (runCatching { linkFocus.getValue(link).requestFocus() }.isSuccess) {
                refocusLink = null
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(100)
        }
        refocusLink = null
    }

    if (showLicenses) {
        androidx.activity.compose.BackHandler { showLicenses = false; refocusLink = TvLink.Licenses }
        // TV: the first row in focus from the start, so the remote scrolls and
        // TalkBack reads at once (before, nothing had the focus until a press).
        val firstRowFocus = remember { FocusRequester() }
        var rowFocused by remember { mutableStateOf(false) }
        if (Device.isTv) {
            LaunchedEffect(Unit) {
                repeat(10) {
                    kotlinx.coroutines.delay(150)
                    if (rowFocused) return@LaunchedEffect
                    runCatching { firstRowFocus.requestFocus() }
                }
            }
        }
        ScreenScaffold(
            title = stringResource(R.string.about_licenses),
            onBack = { showLicenses = false; refocusLink = TvLink.Licenses },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = if (Device.isTv) PaddingValues(vertical = 8.dp) else padding,
                verticalArrangement = Arrangement.spacedBy(if (Device.isTv) 8.dp else 0.dp),
            ) {
                // Plain text, not links, on the phone too: sing-box's releases
                // on GitHub carry APKs, and the app links to no APK outside
                // Google Play (as with our own repository below). TV: each row
                // takes the focus, so the remote scrolls the list and TalkBack
                // reads the row whole.
                itemsIndexed(ossLibraries) { index, lib ->
                    val content: @Composable () -> Unit = {
                        Column(Modifier.padding(16.dp)) {
                            Text(lib.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                            Text(lib.license, fontSize = 14.sp, color = colors.primary)
                            Text(lib.url, fontSize = 13.sp, color = colors.onSurfaceVariant)
                        }
                    }
                    if (Device.isTv) {
                        val type = TvMaterialTheme.typography
                        TvReadRow(
                            modifier = Modifier.fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier)
                                .onFocusChanged { if (it.hasFocus) rowFocused = true },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                TvText(lib.name, style = type.bodyMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
                                TvText(lib.license, style = type.labelSmall, color = colors.primary)
                                TvText(lib.url, style = type.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth().readableWidth(ContentWidth.list)
                                .semantics(mergeDescendants = true) {},
                        ) { content() }
                    }
                }
            }
        }
        return
    }

    // TV: the whole page fits in one screen inside the safe area; scrolled,
    // the focused button pulled the name out of view. A large font makes it
    // taller than the screen: the first button shows the page from its top,
    // the last one to its end, so no line is out of reach (only the buttons
    // take the focus).
    val tv = Device.isTv
    val pageScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    fun scrollTo(end: Boolean): Modifier = if (!tv) Modifier else Modifier.onFocusChanged {
        if (it.isFocused) scope.launch { pageScroll.animateScrollTo(if (end) pageScroll.maxValue else 0) }
    }
    ScreenScaffold(title = stringResource(R.string.title_about), onBack = onBack) { padding ->
        if (tv) {
            TvAboutPage(
                pageScroll = pageScroll,
                linkFocus = linkFocus,
                scrollTo = ::scrollTo,
                onLicenses = { showLicenses = true },
                onLink = { shownLink = it },
            )
            return@ScreenScaffold
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(pageScroll).padding(padding)
                .padding(16.dp).readableWidth(ContentWidth.text),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text("VPN4TV Native", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontSize = 18.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("sing-box core", fontSize = 14.sp, color = colors.onSurfaceVariant)

            Spacer(Modifier.height(40.dp))
            Text(stringResource(R.string.about_license), fontSize = 14.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            // Plain text, NOT a link. Source attribution only. We must not
            // provide an in-app path to any off-Play APK download — the repo's
            // README offers a direct APK, so a clickable link here would be a
            // transitive "link to a noncompliant APK" (Device & Network Abuse).
            Text("github.com/VPN4TV/vpn4tv-native", fontSize = 14.sp, color = colors.onSurfaceVariant)

            Spacer(Modifier.height(24.dp))
            TextButton(
                onClick = { showLicenses = true },
                modifier = Modifier.focusRequester(linkFocus.getValue(TvLink.Licenses)),
            ) {
                Text(stringResource(R.string.about_licenses), fontSize = 16.sp)
            }
            // Google Play's User Data policy: the privacy policy is reachable
            // inside the app. Phone: the page in the browser. TV: a QR code.
            TextButton(onClick = { openUrl(privacyUrl) }) {
                Text(stringResource(R.string.about_privacy), fontSize = 16.sp)
            }

            Spacer(Modifier.height(24.dp))
            val channelLabel = stringResource(R.string.about_telegram)
            Text(channelLabel, fontSize = 16.sp, color = colors.onBackground)
            // Phone: Telegram opens. TV: the channel as a QR code for the phone.
            // TalkBack says the label above before the button's text
            // ("Telegram channel. @VPN4TV"); it reads both.
            TextButton(
                onClick = { openUrl(TELEGRAM_CHANNEL_URL) },
                modifier = Modifier.semantics { contentDescription = channelLabel.trimEnd(':', ' ') },
            ) {
                Text("@VPN4TV", fontSize = 16.sp)
            }

            Spacer(Modifier.height(32.dp))
            // The device id support asks for.
            Text(
                com.vpn4tv.app.converter.HwidService.getHwid(context),
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** The About buttons that open a page over it on TV: Back returns the focus to them. */
private enum class TvLink { Licenses, Privacy, Telegram }

/** The channel the About screen names; the same address on the phone and in the TV's QR code. */
private const val TELEGRAM_CHANNEL_URL = "https://telegram.me/VPN4TV"

/**
 * TV: a link as a QR code and as text, to open on a phone (no browser on a
 * TV, Google's TV-WB). [label]: what to type or look for by hand.
 */
@Composable
private fun LinkQr(title: String, url: String, label: String, hint: String, qrDescription: String) {
    val colors = MaterialTheme.colorScheme
    // In the screen's own pixels, drawn 1:1 (QrImage), as on the import screen.
    val qrSize = 240.dp
    val qrPixels = with(LocalDensity.current) { qrSize.roundToPx() }
    val qrBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, url, qrPixels) {
        value = withContext(Dispatchers.Default) { generateQrBitmap(url, qrPixels) }
    }
    // One stop for the remote, in focus from the start: TalkBack on a TV reads
    // only what has the focus, here the QR's name, the hint and the address.
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(10) {
            kotlinx.coroutines.delay(150)
            if (focused) return@LaunchedEffect
            runCatching { focus.requestFocus() }
        }
    }
    ScreenScaffold(title = title, onBack = null) { padding ->
        Row(
            Modifier.fillMaxSize().padding(padding)
                .focusRequester(focus)
                .onFocusChanged { if (it.hasFocus) focused = true }
                .focusable()
                .clearAndSetSemantics { contentDescription = "$qrDescription. $hint: $label" },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            qrBitmap?.let { QrImage(it, qrSize, qrDescription) }
            Spacer(Modifier.width(40.dp))
            val type = TvMaterialTheme.typography
            Column(Modifier.weight(1f, fill = false)) {
                TvText(hint, style = type.bodyLarge, color = colors.onBackground)
                Spacer(Modifier.height(8.dp))
                TvText(
                    label,
                    style = type.titleLarge, fontWeight = FontWeight.Bold, color = colors.onBackground,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

private data class OssLibrary(val name: String, val license: String, val url: String)

private val ossLibraries = listOf(
    OssLibrary("sing-box", "GPLv3", "github.com/SagerNet/sing-box"),
    OssLibrary("Xray-core", "MPLv2", "github.com/XTLS/Xray-core"),
    OssLibrary("Outline SDK", "Apache 2.0", "github.com/Jigsaw-Code/outline-sdk"),
    OssLibrary("AmneziaWG (amneziawg-go)", "MIT", "github.com/amnezia-vpn/amneziawg-go"),
    OssLibrary("wireproxy", "ISC", "github.com/pufferffish/wireproxy"),
    OssLibrary("Jetpack Compose", "Apache 2.0", "developer.android.com/jetpack/compose"),
    OssLibrary("AndroidX (Room, WorkManager, Lifecycle)", "Apache 2.0", "developer.android.com/jetpack"),
    OssLibrary("Kotlin Coroutines", "Apache 2.0", "github.com/Kotlin/kotlinx.coroutines"),
    OssLibrary("ZXing (QR)", "Apache 2.0", "github.com/zxing/zxing"),
    OssLibrary("Play Core (In-App Update)", "Apache 2.0", "developer.android.com/guide/playcore"),
)

/**
 * TV: the About page in Compose for TV. The page fits in one screen inside the
 * safe area; with a large font the first button shows it from its top and the
 * last one to its end ([scrollTo]), so no line is out of reach (only the
 * buttons take the focus). No browser and no Telegram on a TV (Google's
 * TV-WB): the privacy policy and the channel open as QR codes ([onLink]).
 */
@Composable
private fun TvAboutPage(
    pageScroll: androidx.compose.foundation.ScrollState,
    linkFocus: Map<TvLink, FocusRequester>,
    scrollTo: (Boolean) -> Modifier,
    onLicenses: () -> Unit,
    onLink: (TvLink) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = TvMaterialTheme.colorScheme
    val type = TvMaterialTheme.typography
    Column(
        Modifier.fillMaxSize().verticalScroll(pageScroll),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvText("VPN4TV Native", style = type.displaySmall, fontWeight = FontWeight.Bold, color = colors.onBackground)
        Spacer(Modifier.height(8.dp))
        TvText("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = type.titleSmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        TvText("sing-box core", style = type.labelSmall, color = colors.onSurfaceVariant)

        Spacer(Modifier.height(20.dp))
        TvText(stringResource(R.string.about_license), style = type.labelSmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        // Plain text, NOT a link (see the phone's page).
        TvText("github.com/VPN4TV/vpn4tv-native", style = type.labelSmall, color = colors.onSurfaceVariant)

        Spacer(Modifier.height(12.dp))
        TvTextButton(
            onClick = onLicenses,
            modifier = Modifier.focusRequester(linkFocus.getValue(TvLink.Licenses)).then(scrollTo(false)),
        ) {
            TvText(stringResource(R.string.about_licenses), style = type.bodyMedium)
        }
        TvTextButton(
            onClick = { onLink(TvLink.Privacy) },
            modifier = Modifier.focusRequester(linkFocus.getValue(TvLink.Privacy)),
        ) {
            TvText(stringResource(R.string.about_privacy), style = type.bodyMedium)
        }

        Spacer(Modifier.height(8.dp))
        val channelLabel = stringResource(R.string.about_telegram)
        TvText(channelLabel, style = type.bodyMedium, color = colors.onBackground)
        // TalkBack says the label above before the button's text.
        TvTextButton(
            onClick = { onLink(TvLink.Telegram) },
            modifier = Modifier.focusRequester(linkFocus.getValue(TvLink.Telegram)).then(scrollTo(true))
                .semantics { contentDescription = channelLabel.trimEnd(':', ' ') },
        ) {
            TvText("@VPN4TV", style = type.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))
        // The device id support asks for.
        TvText(
            com.vpn4tv.app.converter.HwidService.getHwid(context),
            style = type.labelSmall, color = colors.onSurfaceVariant,
        )
    }
}
