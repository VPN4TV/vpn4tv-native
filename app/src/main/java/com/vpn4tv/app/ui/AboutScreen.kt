package com.vpn4tv.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vpn4tv.app.BuildConfig
import com.vpn4tv.app.R

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun openUrl(url: String) {
        val full = if (url.startsWith("http")) url else "https://$url"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
    var showLicenses by remember { mutableStateOf(false) }

    if (showLicenses) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = { showLicenses = false }) {
                    Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
                }
                Text(
                    stringResource(R.string.about_licenses),
                    fontSize = 28.sp,
                    color = Color.White,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(ossLibraries) { lib ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .then(if (lib.url.isNotEmpty()) Modifier.clickable { openUrl(lib.url) }.focusable() else Modifier),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(lib.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(lib.license, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            if (lib.url.isNotEmpty()) {
                                Text(lib.url, fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                stringResource(R.string.title_about),
                fontSize = 28.sp,
                color = Color.White,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "VPN4TV Native",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                fontSize = 18.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "sing-box core",
                fontSize = 14.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                stringResource(R.string.about_license),
                fontSize = 14.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "github.com/VPN4TV/vpn4tv-native",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { openUrl("https://github.com/VPN4TV/vpn4tv-native") }
                    .focusable()
            )

            Spacer(modifier = Modifier.height(24.dp))

            TextButton(
                onClick = { showLicenses = true },
                modifier = Modifier.focusable(),
            ) {
                Text(
                    stringResource(R.string.about_licenses),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                stringResource(R.string.about_telegram),
                fontSize = 16.sp,
                color = Color.White
            )
            Text(
                "@VPN4TV",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { openUrl("https://t.me/VPN4TV") }
                    .focusable()
            )
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
