package com.vpn4tv.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.lan.LanImportServer
import com.vpn4tv.app.lan.ProfileImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * LAN import: the user opens a URL (shown + QR) on their phone, pastes a key
 * or subscription link, and it lands on the TV. The bring-your-own-key
 * fallback — reachable from Settings, and surfaced automatically in
 * onboarding only after the Telegram flow fails to reach our server.
 */
@Composable
fun LanImportScreen(onBack: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    var imported by remember { mutableStateOf(false) }
    var importedName by remember { mutableStateOf<String?>(null) }

    // One server per screen instance. onConfig runs on the server thread;
    // ProfileImporter does blocking I/O there, which is fine. On success we
    // flip `imported` so the LaunchedEffect below navigates home.
    val server = remember {
        LanImportServer(onConfig = { config ->
            // onConfig runs on the server thread; importFromText is suspend
            // (Room-backed ProfileManager). runBlocking bridges them — the
            // server handles one request at a time, so blocking that thread
            // until the import finishes is exactly the desired backpressure.
            val result = kotlinx.coroutines.runBlocking {
                ProfileImporter.importFromText(context, config)
            }
            result.onSuccess { name ->
                importedName = name
                imported = true
            }
            result.map { context.getString(R.string.lan_import_added, it) }
        })
    }

    // Stop the VPN on entering: the tunnel's tun interface (172.19.0.x) is
    // site-local and would otherwise compete with the real LAN address, and
    // the import ends by selecting a fresh profile anyway. Mirrors the
    // AddProfileScreen fallback path.
    LaunchedEffect(Unit) {
        if (com.vpn4tv.app.bg.BoxService.globalStatus.value == com.vpn4tv.app.constant.Status.Started) {
            com.vpn4tv.app.bg.BoxService.stop()
        }
    }

    // Tie the server to the Activity lifecycle, not just composition: on a TV,
    // pressing Home sends the Activity to ON_STOP without leaving the
    // composition, so a plain onDispose would leave the import port bound for
    // hours. Bind on ON_START, unbind on ON_STOP. (The server also self-stops
    // after a hard wall-clock backstop.)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> server.start()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> server.stop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            server.stop()
        }
    }

    // Poll the server's current URL continuously: it's null until the socket
    // binds, and changes (new ephemeral port) if the server was stopped on
    // ON_STOP and re-bound on ON_START after a background/foreground cycle.
    var importUrl by remember { mutableStateOf<String?>(null) }
    var probedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            importUrl = server.importUrl()
            probedOnce = true
            delay(500)
        }
    }

    LaunchedEffect(imported) {
        if (imported) {
            delay(1200)
            onImported()
        }
    }

    val qrBitmap by produceState<Bitmap?>(initialValue = null, importUrl) {
        val url = importUrl
        value = if (url == null) null else withContext(Dispatchers.Default) {
            generateQrBitmap(url, 240)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                stringResource(R.string.lan_import_title),
                fontSize = 28.sp, color = Color.White,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        when {
            imported -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("✓", fontSize = 64.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        importedName?.let { stringResource(R.string.lan_import_added, it) }
                            ?: stringResource(R.string.lan_import_done),
                        fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center,
                    )
                }
            }
            importUrl == null -> {
                // probedOnce guards the brief gap before the first poll; once
                // probed, a null URL means no site-local IPv4 (no Wi-Fi/eth).
                if (probedOnce) {
                    Text(
                        stringResource(R.string.lan_import_no_network),
                        fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    )
                }
            }
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    qrBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "QR",
                            modifier = Modifier.size(240.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(40.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.lan_import_step1),
                            fontSize = 16.sp, color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Text(
                                importUrl ?: "",
                                fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace, color = Color.White,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.lan_import_step2),
                            fontSize = 14.sp, color = Color.Gray,
                        )
                    }
                }
            }
        }
    }
}
