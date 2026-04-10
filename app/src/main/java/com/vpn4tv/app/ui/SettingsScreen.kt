package com.vpn4tv.app.ui

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpn4tv.app.R
import com.vpn4tv.app.database.Settings

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var autoConnect by remember { mutableStateOf(Settings.autoConnectOnBoot) }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                stringResource(R.string.title_settings),
                fontSize = 28.sp,
                color = Color.White,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Auto-connect on boot
        SettingsToggle(
            title = stringResource(R.string.setting_auto_connect),
            subtitle = stringResource(R.string.setting_auto_connect_desc),
            checked = autoConnect,
            onCheckedChange = {
                autoConnect = it
                Settings.autoConnectOnBoot = it
            }
        )
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onCheckedChange(!checked) }
            .focusable(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, color = Color.White)
                Text(subtitle, fontSize = 13.sp, color = Color.Gray)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
