package com.vpn4tv.app.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.io.File
import java.net.URL
import java.util.UUID

@Composable
fun AddProfileScreen(onBack: () -> Unit, onProfileAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val uuid = remember { UUID.randomUUID().toString() }
    val code10 = remember { (0 until 10).map { (0..9).random() }.joinToString("") }
    val formattedCode = remember {
        "${code10[0]} ${code10.substring(1, 4)} ${code10.substring(4, 7)} ${code10.substring(7)}"
    }

    var status by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf<String?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var isDone by remember { mutableStateOf(false) }

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
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !isDone) {
                scope.launch(Dispatchers.IO) {
                    pollServer(uuid)?.let { data ->
                        handlePollResponse(data, context, scope,
                            onUserInfo = { name -> userName = name; status = name },
                            onConfig = { configs ->
                                isAdding = true; status = "adding"
                                scope.launch(Dispatchers.IO) {
                                    var errorMsg = ""
                                    val added = processConfigs(context, configs, userName) { errorMsg = it }
                                    withContext(Dispatchers.Main) {
                                        if (added) { isDone = true; status = "done"; delay(1000); onProfileAdded() }
                                        else { isAdding = false; status = if (errorMsg.isNotEmpty()) "error:$errorMsg" else "failed" }
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
            while (isActive && !isDone) {
                // Poll UUID
                pollServer(uuid)?.let { data ->
                    handlePollResponse(data, context, scope,
                        onUserInfo = { name -> userName = name; status = name },
                        onConfig = { configs ->
                            isAdding = true
                            status = "adding"
                            scope.launch(Dispatchers.IO) {
                                var errorMsg = ""
                                val added = processConfigs(context, configs, userName) { errorMsg = it }
                                withContext(Dispatchers.Main) {
                                    if (added) {
                                        isDone = true
                                        status = "done"
                                        delay(1000)
                                        onProfileAdded()
                                    } else {
                                        isAdding = false
                                        status = if (errorMsg.isNotEmpty()) "error:$errorMsg" else "failed"
                                    }
                                }
                            }
                        }
                    )
                }

                // Poll code
                if (!isDone) {
                    pollServer(code10)?.let { data ->
                        handlePollResponse(data, context, scope,
                            onUserInfo = { name -> userName = name; status = name },
                            onConfig = { configs ->
                                isAdding = true
                                status = "adding"
                                scope.launch(Dispatchers.IO) {
                                    val added = processConfigs(context, configs, userName)
                                    withContext(Dispatchers.Main) {
                                        if (added) {
                                            isDone = true
                                            status = "done"
                                            delay(1000)
                                            onProfileAdded()
                                        } else {
                                            isAdding = false
                                            status = "failed"
                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                delay(5000) // Poll every 5 seconds
            }
        }
        onDispose { job.cancel() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                stringResource(R.string.title_add_subscription),
                fontSize = 28.sp,
                color = Color.White,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Detect phone vs TV
        val isTV = remember {
            context.packageManager.hasSystemFeature("android.software.leanback")
        }

        val qrBitmap = remember(uuid) {
            generateQrBitmap("https://t.me/VPN4TV_Bot?start=$uuid", 200)
        }

        if (isTV) {
            // TV: QR + Code side by side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(200.dp)
                    )
                }
                Spacer(modifier = Modifier.width(48.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.add_send_code), fontSize = 18.sp, color = Color.White)
                    Text("@VPN4TV_Bot", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(formattedCode, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            color = Color.White, textAlign = TextAlign.Center, letterSpacing = 3.sp,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.add_scan_qr), fontSize = 14.sp, color = Color.Gray)
                }
            }
        } else {
            // Phone: vertical layout, Telegram button
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.add_send_code), fontSize = 18.sp, color = Color.White)
                Text("@VPN4TV_Bot", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(formattedCode, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        color = Color.White, textAlign = TextAlign.Center, letterSpacing = 2.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Open Telegram button (phone only)
                Button(
                    onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://t.me/VPN4TV_Bot?start=$uuid"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
                ) {
                    Text(stringResource(R.string.open_telegram_bot), fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (qrBitmap != null) {
                    Text(stringResource(R.string.add_scan_qr), fontSize = 14.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(150.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Status
        if (isAdding) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(8.dp))
        }
        val statusText = when {
            status == "" -> stringResource(R.string.add_waiting)
            status == "adding" -> stringResource(R.string.add_adding)
            status == "done" -> stringResource(R.string.add_success)
            status == "failed" -> stringResource(R.string.add_failed)
            status.startsWith("error:") -> status.removePrefix("error:")
            else -> status // user name from Telegram
        }
        Text(
            statusText,
            fontSize = 16.sp,
            color = when {
                isDone -> Color.Green
                status.startsWith("error:") || status == "failed" -> Color.Red
                userName != null -> MaterialTheme.colorScheme.primary
                else -> Color.Gray
            },
            textAlign = TextAlign.Center
        )
    }
}

private suspend fun pollServer(id: String): JSONObject? {
    return withContext(Dispatchers.IO) {
        try {
            val response = URL("https://api.vpn4tv.com/poll?uuid=$id").readText()
            val json = JSONObject(response)
            if (json.optString("type") != "timeout") json else null
        } catch (e: Exception) {
            null
        }
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
                val sub = com.vpn4tv.app.converter.HwidService.fetchSubscription(context, trimmed)
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
        // finally "Subscription". Deduped via uniqueName so repeat adds get
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
            else -> "Subscription"
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
        Settings.selectedProfile = profile.id

        Log.d("AddProfile", "Added ${profile.name}: ${proxies.size} proxies (selected)")
        true
    } catch (e: Exception) {
        Log.e("AddProfile", "Failed to process configs", e)
        onError?.invoke("${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT)
            }
        }
        bitmap
    } catch (e: Exception) {
        Log.e("QR", "Failed to generate QR: ${e.message}")
        null
    }
}
