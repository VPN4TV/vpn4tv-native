package com.vpn4tv.app.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vpn4tv.app.bg.BoxService
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.converter.ConfigGenerator
import com.vpn4tv.app.converter.ProxyParser
import com.vpn4tv.app.database.Profile
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.database.TypedProfile
import kotlinx.coroutines.*
import org.json.JSONObject
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import java.io.File
import java.net.URL
import java.util.UUID

/** The QR's colours: black modules on white, in both themes and on TV. */
internal const val QR_DARK = 0xFF000000.toInt()
internal const val QR_LIGHT = 0xFFFFFFFF.toInt()

@Composable
fun AddProfileScreen(onBack: () -> Unit, onProfileAdded: () -> Unit, onLanImport: () -> Unit = {}) {
    // TV: the code in focus from the start, so TalkBack reads it at once; asked
    // again for a moment, a request during the enter transition is dropped.
    val codeFocus = remember { FocusRequester() }
    var codeFocused by remember { mutableStateOf(false) }
    if (Device.isTv) {
        LaunchedEffect(Unit) {
            repeat(10) {
                delay(200)
                if (codeFocused) return@LaunchedEffect
                runCatching { codeFocus.requestFocus() }
            }
        }
    }
    ScreenScaffold(title = stringResource(R.string.title_add_subscription), onBack = onBack) { padding ->
        AddKeyContent(
            onProfileAdded = onProfileAdded,
            onLanImport = onLanImport,
            codeFocus = codeFocus,
            onCodeFocused = { codeFocused = true },
            // Phone and tablet: the bot button and the hints no wider than a
            // readable column on a wide window.
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)
                .padding(horizontal = if (Device.isTv) 0.dp else 24.dp).readableWidth(ContentWidth.button + 80.dp),
        )
    }
}

/**
 * Adding a key through the bot: the code and the QR, the wait for the bot's
 * answer, the import. The add-key screen shows it, and on TV so does Home
 * while there is no key at all: the first screen is then also the last one
 * before the TV's home screen (Google's TV navigation, "Fixed start
 * destination"), with no empty Home under it.
 *
 * TV: the code with its instructions is one stop for the remote ([codeFocus]
 * asks for it, [onCodeFocused] says it has it), so TalkBack reads all of it,
 * the code digit by digit; the status line is read out as it changes.
 */
@Composable
fun AddKeyContent(
    onProfileAdded: () -> Unit,
    onLanImport: () -> Unit,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    codeFocus: FocusRequester? = null,
    onCodeFocused: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Saved with the screen: a turn, a theme change or the system dropping the
    // app while the user is in Telegram recreate the screen (a keyboard or a
    // box's remote waking up no longer does: configChanges in the manifest),
    // and the bot answers the code it was sent; a new code and uuid would leave
    // this screen waiting for ever.
    val uuid = rememberSaveable { UUID.randomUUID().toString() }
    val code10 = rememberSaveable { (0 until 10).map { (0..9).random() }.joinToString("") }
    val formattedCode = remember(code10) {
        "${code10[0]} ${code10.substring(1, 4)} ${code10.substring(4, 7)} ${code10.substring(7)}"
    }

    // The Telegram name the bot sent is kept as well; the rest starts afresh
    // (an import cut off by the recreation is redone from the server's copy).
    var userName by rememberSaveable { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(userName ?: "") }
    var isAdding by remember { mutableStateOf(false) }
    var isDone by remember { mutableStateOf(false) }
    // One import per screen: the server gives a key again for 3 minutes
    // (redeliver=1), and the resume poll and the loop's poll may both get it —
    // it must not be added twice. Cleared when an import fails, so the key
    // given again is tried again.
    val importStarted = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // A key whose import failed comes again from the server for 3 minutes: it
    // is tried twice more, then only a different key from the bot is taken
    // (as on the iPhone; no flicker of error and adding for 3 minutes).
    val failedConfigs = remember { java.util.concurrent.atomic.AtomicReference<List<String>?>(null) }
    val failedRepeats = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    fun mayImport(configs: List<String>): Boolean {
        if (isDone || !importStarted.compareAndSet(false, true)) return false
        if (configs == failedConfigs.get() && failedRepeats.getAndIncrement() >= 2) {
            importStarted.set(false)
            return false
        }
        return true
    }
    fun importFailed(configs: List<String>) {
        if (failedConfigs.getAndSet(configs) != configs) failedRepeats.set(0)
        importStarted.set(false)
    }
    // Retry progress shown in red while subscription fetch is timing out —
    // user sees "Попытка N/10 (через резервный сервер)" instead of a frozen UI.
    var retryInfo by remember { mutableStateOf<com.vpn4tv.app.converter.HwidService.RetryProgress?>(null) }
    // Consecutive poll iterations where BOTH endpoints were network-unreachable
    // (ISP blocking our control server / DNS). After a threshold we surface the
    // LAN-import fallback — the only onboarding path that needs no internet.
    var pollNetFailures by remember { mutableStateOf(0) }
    val showLanFallback = pollNetFailures >= 3

    // Disconnect any active session before we start polling. A user adding
    // a SECOND subscription was seeing "бесконечное ожидание" because the
    // VPN tunnel was routing api.vpn4tv.com through the old server — which
    // may block / rate-limit the bot API or just fail DNS for the control
    // plane. Tearing the tunnel down first makes /poll hit the underlying
    // network directly. No-op if nothing is running.
    LaunchedEffect(Unit) {
        if (BoxService.globalStatus.value == Status.Started) {
            BoxService.stop()
        }
    }

    // Force poll on app resume (after returning from Telegram)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            // Not while an import runs: the server's copy would only be dropped.
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !isDone && !importStarted.get()) {
                scope.launch(Dispatchers.IO) {
                    val outcome = pollServer(uuid)
                    if (outcome is PollOutcome.Data) {
                        handlePollResponse(outcome.json, context, scope,
                            onUserInfo = { name -> userName = name; status = name },
                            onConfig = onConfig@{ configs ->
                                if (!mayImport(configs)) return@onConfig
                                isAdding = true; status = "adding"
                                scope.launch(Dispatchers.IO) {
                                    var errorMsg = ""
                                    val added = processConfigs(
                                        context, configs, userName,
                                        onError = { errorMsg = it },
                                        onProgress = { retryInfo = it },
                                    )
                                    withContext(Dispatchers.Main) {
                                        retryInfo = null
                                        if (added) { isDone = true; status = "done"; delay(1000); onProfileAdded() }
                                        else { importFailed(configs); isAdding = false; status = if (errorMsg.isNotEmpty()) "error:$errorMsg" else "failed" }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Polling both UUID and code
    DisposableEffect(Unit) {
        val job = scope.launch {
            val onConfigArrived: (List<String>) -> Unit = onConfigArrived@{ configs ->
                if (!mayImport(configs)) return@onConfigArrived
                isAdding = true
                status = "adding"
                scope.launch(Dispatchers.IO) {
                    var errorMsg = ""
                    val added = processConfigs(
                        context, configs, userName,
                        onError = { errorMsg = it },
                        onProgress = { retryInfo = it },
                    )
                    withContext(Dispatchers.Main) {
                        retryInfo = null
                        if (added) {
                            isDone = true
                            status = "done"
                            delay(1000)
                            onProfileAdded()
                        } else {
                            importFailed(configs)
                            isAdding = false
                            status = if (errorMsg.isNotEmpty()) "error:$errorMsg" else "failed"
                        }
                    }
                }
            }
            while (isActive && !isDone) {
                val uuidOutcome = pollServer(uuid)
                if (uuidOutcome is PollOutcome.Data) {
                    handlePollResponse(uuidOutcome.json, context, scope,
                        onUserInfo = { name -> userName = name; status = name },
                        onConfig = onConfigArrived)
                }

                val codeOutcome = if (!isDone) pollServer(code10) else PollOutcome.Empty
                if (codeOutcome is PollOutcome.Data) {
                    handlePollResponse(codeOutcome.json, context, scope,
                        onUserInfo = { name -> userName = name; status = name },
                        onConfig = onConfigArrived)
                }

                // Count an iteration as a network failure only when BOTH polls
                // failed to reach any endpoint — a single mirror hiccup doesn't
                // trip the LAN fallback. Any successful contact resets it.
                if (uuidOutcome is PollOutcome.NetworkError && codeOutcome is PollOutcome.NetworkError) {
                    pollNetFailures++
                } else {
                    pollNetFailures = 0
                }

                delay(5000) // Poll every 5 seconds
            }
        }
        onDispose { job.cancel() }
    }

    // QR encoding + ICU charset init can block main thread >5s on slow
    // TV SoCs (Xiaomi MiTV ANR, vc50307). Generate off-thread, in the screen's
    // own pixels (see QrImage).
    val qrSize = if (Device.isTv) 200.dp else 180.dp
    val qrPixels = with(LocalDensity.current) { qrSize.roundToPx() }
    val qrBitmap by produceState<Bitmap?>(initialValue = null, uuid, qrPixels) {
        value = withContext(Dispatchers.Default) {
            generateQrBitmap("https://telegram.me/VPN4TV_Bot?start=$uuid", qrPixels)
        }
    }
    val colors = MaterialTheme.colorScheme
    val state = com.vpn4tv.app.ui.theme.LocalStateColors.current
    var showQr by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = verticalArrangement,
    ) {
        Spacer(Modifier.height(16.dp))
        if (Device.isTv) {
            // TV: the code is typed in Telegram on a phone, or the QR is
            // scanned with it — side by side. One focus stop: TalkBack on
            // a TV reads only what has the focus, so the whole block is
            // said at once, the code digit by digit (Google's TV TalkBack
            // check: a code to enter on another device can be followed
            // character by character); the frame marks the code.
            var codeFocused by remember { mutableStateOf(false) }
            val sendCode = stringResource(R.string.add_send_code)
            val scanQr = stringResource(R.string.add_scan_qr)
            val enterOnWeb = stringResource(R.string.add_enter_on_web)
            val spoken = "$sendCode $BOT_NAME: ${spokenCode(formattedCode)}. " +
                "${scanQr.replaceFirstChar { it.uppercase() }}, $enterOnWeb"
            Row(
                modifier = Modifier.fillMaxWidth()
                    .then(if (codeFocus != null) Modifier.focusRequester(codeFocus) else Modifier)
                    .onFocusChanged {
                        codeFocused = it.hasFocus
                        if (it.hasFocus) onCodeFocused()
                    }
                    .focusable()
                    .clearAndSetSemantics { contentDescription = spoken },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                qrBitmap?.let { bmp ->
                    QrImage(bmp, qrSize, stringResource(R.string.qr_bot_link))
                }
                Spacer(Modifier.width(48.dp))
                val type = TvMaterialTheme.typography
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TvText(sendCode, style = type.titleMedium, color = colors.onBackground)
                    TvText(BOT_NAME, style = type.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                    Spacer(Modifier.height(16.dp))
                    CodeCard(formattedCode, 40.sp, Modifier.tvFocusMark(codeFocused, CodeCardShape, 1.05f))
                    Spacer(Modifier.height(12.dp))
                    TvText(scanQr, style = type.bodyMedium, color = colors.onSurfaceVariant)
                    TvText(enterOnWeb, style = type.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        } else {
            // Phone: the bot opens right here; a QR on this very screen
            // could only be scanned by another device, so it waits behind
            // a button.
            Text(stringResource(R.string.add_send_code), style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground, textAlign = TextAlign.Center)
            Text(BOT_NAME, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
            Spacer(Modifier.height(16.dp))
            CodeCard(formattedCode, 28.sp)
            TextButton(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VPN4TV", code10))
                // Android 13+ confirms a copy itself.
                if (android.os.Build.VERSION.SDK_INT < 33) AppMessages.post(AppMessage.CodeCopied)
            }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_copy_code))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://telegram.me/VPN4TV_Bot?start=$uuid")))
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(R.string.open_telegram_bot), fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.phone_add_hint), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showQr = !showQr }) {
                Text(stringResource(if (showQr) R.string.action_hide_qr else R.string.action_show_qr))
            }
            if (showQr) {
                qrBitmap?.let { bmp ->
                    QrImage(bmp, qrSize, stringResource(R.string.qr_bot_link))
                }
                Text(stringResource(R.string.add_enter_on_web), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Status
        if (isAdding) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            retryInfo?.let { p ->
                if (p.attempt > 1) {
                    val src = if (p.mirror) stringResource(R.string.add_retry_mirror)
                              else stringResource(R.string.add_retry_primary)
                    val retry = "${stringResource(R.string.add_retry_prefix)} ${p.attempt}/${p.maxAttempts} ($src)"
                    if (Device.isTv) {
                        TvText(retry, style = TvMaterialTheme.typography.labelSmall, color = state.danger, textAlign = TextAlign.Center)
                    } else {
                        Text(retry, fontSize = 14.sp, color = state.danger, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        val statusText = when {
            status == "" -> stringResource(R.string.add_waiting)
            status == "adding" -> stringResource(R.string.add_adding)
            status == "done" -> stringResource(R.string.add_success)
            status == "failed" -> stringResource(R.string.add_failed)
            status.startsWith("error:") -> status.removePrefix("error:")
            else -> status // user name from Telegram
        }
        val statusColor = when {
            isDone -> state.connected
            status.startsWith("error:") || status == "failed" -> state.danger
            userName != null -> colors.primary
            else -> colors.onSurfaceVariant
        }
        // TalkBack says "Adding the key…", "Key added!" or the error
        // as it comes, wherever the focus is.
        val announced = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        if (Device.isTv) {
            TvText(statusText, style = TvMaterialTheme.typography.bodyLarge, color = statusColor,
                textAlign = TextAlign.Center, modifier = announced)
        } else {
            Text(statusText, fontSize = 16.sp, color = statusColor, textAlign = TextAlign.Center, modifier = announced)
        }

        // LAN-import fallback: appears only after repeated failures to reach
        // our control server (ISP blocking the bot API / DNS). The Telegram
        // funnel stays primary; this is the escape hatch so a blocked user
        // isn't dead-ended on the very first screen.
        if (showLanFallback && !isAdding && !isDone) {
            Spacer(Modifier.height(24.dp))
            if (Device.isTv) {
                val type = TvMaterialTheme.typography
                TvText(
                    stringResource(R.string.add_lan_fallback_hint), style = type.labelSmall,
                    color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TvOutlinedButton(
                    onClick = onLanImport,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    focusedScale = 1.03f,
                    fillWidth = true,
                ) {
                    TvText(stringResource(R.string.add_lan_fallback_button), style = type.bodyMedium)
                }
            } else {
                Text(
                    stringResource(R.string.add_lan_fallback_hint),
                    fontSize = 14.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onLanImport, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                    Text(stringResource(R.string.add_lan_fallback_button), fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private const val BOT_NAME = "@VPN4TV_Bot"

private val CodeCardShape = RoundedCornerShape(12.dp)

/**
 * The code for TalkBack: digit by digit, the groups apart as on the screen
 * ("1 234 567 890" is said "1, 2 3 4, 5 6 7, 8 9 0"), not as numbers in the
 * hundreds that nobody can type from.
 */
internal fun spokenCode(code: String): String =
    code.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .joinToString(", ") { group -> group.toList().joinToString(" ") }

@Composable
private fun CodeCard(code: String, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    if (Device.isTv) {
        TvPanel(modifier = modifier, shape = CodeCardShape) {
            TvText(
                code, style = TvMaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, letterSpacing = 2.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    .semantics { contentDescription = spokenCode(code) },
            )
        }
        return
    }
    Card(
        modifier = modifier,
        shape = CodeCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Text(
            code, fontSize = size, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, letterSpacing = 2.sp,
            // TalkBack on a phone: the digits one by one, as on TV.
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                .semantics { contentDescription = spokenCode(code) },
        )
    }
}

// Distinguishes "no data yet" (server reachable, nothing queued) from
// "couldn't reach any endpoint" (ISP blocking us) so the caller can decide
// when to surface the LAN-import fallback.
private sealed class PollOutcome {
    data class Data(val json: JSONObject) : PollOutcome()
    object Empty : PollOutcome()        // reached server, type=timeout / nothing
    object NetworkError : PollOutcome() // every endpoint threw
}

private suspend fun pollServer(id: String): PollOutcome {
    return withContext(Dispatchers.IO) {
        // redeliver=1: the server keeps a key it handed out for 3 minutes and
        // gives it again to this id's next poll (an answer can be lost on the
        // way, as on a frozen iPhone app, 2026-11-08); safe with importStarted.
        val primaryUrl = "https://api.vpn4tv.com/poll?uuid=$id&redeliver=1"
        val endpoints = com.vpn4tv.app.converter.HwidService.endpointsWithMirror(primaryUrl)
        var reachedAny = false
        for (url in endpoints) {
            try {
                val conn = com.vpn4tv.app.converter.HwidService
                    .openConnectionWithDnsFallbackPublic(url)
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                reachedAny = true
                val json = JSONObject(response)
                if (json.optString("type") != "timeout") return@withContext PollOutcome.Data(json)
            } catch (_: Exception) {
                // try next endpoint (mirror)
            }
        }
        if (reachedAny) PollOutcome.Empty else PollOutcome.NetworkError
    }
}

private fun handlePollResponse(
    data: JSONObject,
    context: android.content.Context,
    scope: CoroutineScope,
    onUserInfo: (String) -> Unit,
    onConfig: (List<String>) -> Unit,
) {
    when (data.optString("type")) {
        "user_info" -> {
            val userData = data.optJSONObject("data")
            val name = listOfNotNull(
                userData?.optString("first_name"),
                userData?.optString("last_name")
            ).joinToString(" ")
            onUserInfo(name)
        }
        "vpn_config_processed" -> {
            val configArray = data.optJSONArray("config") ?: return
            val configs = (0 until configArray.length()).map { configArray.getString(it) }
            onConfig(configs)
        }
    }
}

private suspend fun processConfigs(
    context: android.content.Context,
    configs: List<String>,
    userName: String?,
    onError: ((String) -> Unit)? = null,
    onProgress: ((com.vpn4tv.app.converter.HwidService.RetryProgress) -> Unit)? = null,
): Boolean {
    return try {
        // Configs can be URLs (https://...) or direct proxy links (vless://...)
        // Collect all proxy content. Also capture metadata from the first
        // subscription response: profile-title, update interval, support URL.
        // Hiddify-style subscription servers advertise these as HTTP headers.
        val allContent = StringBuilder()
        var remoteUrl = ""
        var headerTitle: String? = null
        var headerUpdateHours: Int? = null

        for (config in configs) {
            val trimmed = config.trim()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val sub = com.vpn4tv.app.converter.HwidService.fetchSubscription(context, trimmed, onProgress)
                allContent.appendLine(sub.body)
                if (remoteUrl.isEmpty()) {
                    remoteUrl = trimmed
                    headerTitle = sub.title
                    headerUpdateHours = sub.updateIntervalHours
                }
            } else {
                // Direct proxy link (vless://, ss://, etc.) or config
                allContent.appendLine(trimmed)
            }
        }

        val content = allContent.toString().trim()
        if (content.isEmpty()) return false

        val proxies = ProxyParser.parseSubscription(content)
        if (proxies.isEmpty()) {
            onError?.invoke("No valid proxies found")
            return false
        }

        val profilesDir = File(context.filesDir, "profiles")
        profilesDir.mkdirs()
        val nextId = ProfileManager.nextFileID()
        val configPath = File(profilesDir, "${nextId}.json").absolutePath

        val result = ConfigGenerator.generateFull(proxies)
        ConfigGenerator.writeAll(configPath, result)

        // Naming priority: HTTP `profile-title` header (the canonical Hiddify
        // convention — decoded from base64 if needed), then the legacy inline
        // `#profile-title:` marker, then the Telegram display name, then the
        // subscription URL host (xo.e0f.cx is a better fallback than a
        // random proxy tag), then the first proxy's tag, then its server host,
        // finally "Key". Deduped via uniqueName so repeat adds get
        // " 2", " 3", … suffixes.
        val inlineTitle = content.lines()
            .firstOrNull { it.startsWith("#profile-title:") }
            ?.substringAfter("#profile-title:")?.trim()
        val urlHost = if (remoteUrl.isNotEmpty()) {
            try { java.net.URL(remoteUrl).host } catch (_: Exception) { "" }
        } else ""
        val firstProxy = proxies.firstOrNull()
        val firstHost = firstProxy?.server?.trim().orEmpty()
        val firstTag = firstProxy?.tag?.trim().orEmpty()
        val baseName = when {
            !headerTitle.isNullOrBlank() -> headerTitle
            !inlineTitle.isNullOrBlank() -> inlineTitle
            !userName.isNullOrBlank() -> userName
            urlHost.isNotEmpty() -> urlHost
            firstTag.isNotEmpty() && !isGenericProxyTag(firstTag) -> firstTag
            firstHost.isNotEmpty() && firstTag.isNotEmpty() -> "$firstTag ($firstHost)"
            firstHost.isNotEmpty() -> firstHost
            firstTag.isNotEmpty() -> firstTag
            else -> "Key"
        }
        val profileName = ProfileManager.uniqueName(baseName)

        val profile = ProfileManager.create(
            Profile(name = profileName, userOrder = ProfileManager.nextOrder()).apply {
                typed.type = if (remoteUrl.isNotEmpty()) TypedProfile.Type.Remote else TypedProfile.Type.Local
                typed.remoteURL = remoteUrl
                typed.path = configPath
                typed.autoUpdate = remoteUrl.isNotEmpty()
                // profile-update-interval header is in hours; TypedProfile
                // stores it in minutes. Clamp to at least 10 minutes so a
                // misconfigured "0" header doesn't spin the updater.
                typed.autoUpdateInterval = headerUpdateHours
                    ?.let { (it.coerceAtLeast(1) * 60) }
                    ?: 60
                typed.lastUpdated = java.util.Date()
            }
        )

        // Always switch the active profile to the freshly-added one. Users
        // who add a second subscription expect the TV to start using it
        // immediately — leaving the old profile selected made the "add
        // another key" flow look broken ("it says done but nothing changed").
        // The profile row exists now: a failed selection must not report the
        // import as failed, or the server's copy of the key would add it again.
        runCatching { Settings.selectedProfile = profile.id }
            .onFailure { Log.e("AddProfile", "Select the added profile failed", it) }

        Log.d("AddProfile", "Added ${profile.name}: ${proxies.size} proxies (selected)")
        true
    } catch (e: Exception) {
        Log.e("AddProfile", "Failed to process configs: ${com.vpn4tv.app.utils.LogPrivacy.error(e)}")
        onError?.invoke("${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

/**
 * A QR code as any scanner reads it, whatever the theme: dark modules on a
 * white square with its quiet zone (ZXing's default margin). White modules on
 * a transparent background vanished on the light theme (5.5.0). [size] is in
 * pixels: ZXing makes every module a whole number of them.
 */
internal fun generateQrBitmap(content: String, size: Int): Bitmap? {
    val pixels = qrPixels(content, size) ?: return null
    return try {
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
    } catch (e: Exception) {
        Log.e("QR", "Failed to generate QR: ${e.message}")
        null
    }
}

/** The pixels of [generateQrBitmap], row by row: [QR_DARK] modules on [QR_LIGHT]. */
internal fun qrPixels(content: String, size: Int): IntArray? {
    return try {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        // Smaller than the code needs, ZXing returns its own larger matrix.
        if (bitMatrix.width != size || bitMatrix.height != size) return null
        IntArray(size * size) { i -> if (bitMatrix[i % size, i / size]) QR_DARK else QR_LIGHT }
    } catch (e: Exception) {
        Log.e("QR", "Failed to generate QR: ${e.message}")
        null
    }
}

/**
 * The QR as large on the screen as its picture is in pixels ([generateQrBitmap]
 * with the screen's pixels for [size]), drawn without smoothing: a 200 px
 * picture stretched to 200 dp had soft module edges, blown up four times on a
 * 4K TV, and a sharp code is read faster by a phone's camera (TV-4K).
 */
@Composable
internal fun QrImage(bitmap: Bitmap, size: Dp, contentDescription: String) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = contentDescription,
        modifier = Modifier.size(size),
        filterQuality = FilterQuality.None,
    )
}
