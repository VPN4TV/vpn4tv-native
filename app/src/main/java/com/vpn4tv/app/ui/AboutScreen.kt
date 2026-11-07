package com.vpn4tv.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.BuildConfig
import com.vpn4tv.app.R

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun openUrl(url: String) {
        val full = if (url.startsWith("http")) url else "https://$url"
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }
    var showLicenses by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    if (showLicenses) {
        androidx.activity.compose.BackHandler { showLicenses = false }
        ScreenScaffold(title = stringResource(R.string.about_licenses), onBack = { showLicenses = false }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = if (Device.isTv) PaddingValues(vertical = 8.dp) else padding,
                verticalArrangement = Arrangement.spacedBy(if (Device.isTv) 8.dp else 0.dp),
            ) {
                items(ossLibraries) { lib ->
                    val content: @Composable () -> Unit = {
                        Column(Modifier.padding(16.dp)) {
                            Text(lib.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                            Text(lib.license, fontSize = 14.sp, color = colors.primary)
                            Text(lib.url, fontSize = 13.sp, color = colors.onSurfaceVariant)
                        }
                    }
                    Surface(
                        onClick = { openUrl(lib.url) },
                        modifier = Modifier.fillMaxWidth().tvFocus(RoundedCornerShape(12.dp), TV_ROW_SCALE),
                        shape = RoundedCornerShape(12.dp),
                        color = if (Device.isTv) colors.surfaceContainerHigh else colors.background,
                        content = content,
                    )
                }
            }
        }
        return
    }

    // TV: the whole page fits in one screen inside the safe area; scrolled,
    // the focused button pulled the name out of view.
    val tv = Device.isTv
    ScreenScaffold(title = stringResource(R.string.title_about), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)
                .padding(if (tv) 0.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (tv) 0.dp else 24.dp))
            Text("VPN4TV Native", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontSize = 18.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("sing-box core", fontSize = 14.sp, color = colors.onSurfaceVariant)

            Spacer(Modifier.height(if (tv) 20.dp else 40.dp))
            Text(stringResource(R.string.about_license), fontSize = 14.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            // Plain text, NOT a link. Source attribution only. We must not
            // provide an in-app path to any off-Play APK download — the repo's
            // README offers a direct APK, so a clickable link here would be a
            // transitive "link to a noncompliant APK" (Device & Network Abuse).
            Text("github.com/VPN4TV/vpn4tv-native", fontSize = 14.sp, color = colors.onSurfaceVariant)

            Spacer(Modifier.height(if (tv) 12.dp else 24.dp))
            TextButton(onClick = { showLicenses = true }, modifier = Modifier.tvFocus(CircleShape)) {
                Text(stringResource(R.string.about_licenses), fontSize = 16.sp)
            }

            Spacer(Modifier.height(if (tv) 8.dp else 24.dp))
            Text(stringResource(R.string.about_telegram), fontSize = 16.sp, color = colors.onBackground)
            TextButton(onClick = { openUrl("https://telegram.me/VPN4TV") }, modifier = Modifier.tvFocus(CircleShape)) {
                Text("@VPN4TV", fontSize = 16.sp)
            }

            Spacer(Modifier.height(if (tv) 12.dp else 32.dp))
            // The device id support asks for.
            Text(
                com.vpn4tv.app.converter.HwidService.getHwid(context),
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
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
