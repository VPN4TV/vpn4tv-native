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

        // QR + Code side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // QR Code
            val qrBitmap = remember(uuid) {
                generateQrBitmap("https://t.me/VPN4TV_Bot?start=$uuid", 200)
            }
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(200.dp)
                )
            }

            Spacer(modifier = Modifier.width(48.dp))

            // Code + instructions
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.add_send_code),
                    fontSize = 18.sp,
                    color = Color.White
                )
                Text(
                    "@VPN4TV_Bot",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        formattedCode,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        letterSpacing = 3.sp,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.add_scan_qr),
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Status
        if (isAdding) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(8.dp))
        }
        val statusText = when (status) {
            "" -> stringResource(R.string.add_waiting)
            "adding" -> stringResource(R.string.add_adding)
            "done" -> stringResource(R.string.add_success)
            "failed" -> stringResource(R.string.add_failed)
            else -> status // user name from Telegram
        }
        Text(
            statusText,
            fontSize = 16.sp,
            color = when {
                isDone -> Color.Green
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
): Boolean {
    return try {
        for (configUrl in configs) {
            val subContent = URL(configUrl).readText()
            val proxies = ProxyParser.parseSubscription(subContent)
            if (proxies.isEmpty()) continue

            val profilesDir = File(context.filesDir, "profiles")
            profilesDir.mkdirs()
            val nextId = ProfileManager.nextFileID()
            val configPath = File(profilesDir, "${nextId}.json").absolutePath

            val singboxConfig = ConfigGenerator.generate(proxies)
            File(configPath).writeText(singboxConfig)

            // Extract name from subscription headers
            val profileName = subContent.lines()
                .firstOrNull { it.startsWith("#profile-title:") }
                ?.substringAfter("#profile-title:")?.trim()
                ?: userName
                ?: "Subscription"

            val profile = ProfileManager.create(
                Profile(name = profileName, userOrder = ProfileManager.nextOrder()).apply {
                    typed.type = TypedProfile.Type.Remote
                    typed.remoteURL = configUrl
                    typed.path = configPath
                    typed.autoUpdate = true
                    typed.autoUpdateInterval = 60
                    typed.lastUpdated = java.util.Date()
                }
            )

            if (Settings.selectedProfile == -1L) {
                Settings.selectedProfile = profile.id
            }

            Log.d("AddProfile", "Added ${profile.name}: ${proxies.size} proxies from $configUrl")
        }
        true
    } catch (e: Exception) {
        Log.e("AddProfile", "Failed to process configs", e)
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
