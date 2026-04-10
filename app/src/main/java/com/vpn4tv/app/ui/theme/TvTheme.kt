package com.vpn4tv.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Material3 dark scheme with purple seed matching VPN4TV logo
private val TvDarkColors = darkColorScheme(
    primary = Color(0xFFB39DFF),
    onPrimary = Color(0xFF2A0080),
    primaryContainer = Color(0xFF5C3BBF),
    onPrimaryContainer = Color(0xFFE8DEFF),

    background = Color(0xFF0E0E0E),
    onBackground = Color(0xFFE1E1E1),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE1E1E1),
    surfaceVariant = Color(0xFF252525),
    onSurfaceVariant = Color(0xFFBBBBBB),

    secondary = Color(0xFF9FA8DA),
    secondaryContainer = Color(0xFF2E3556),
    onSecondaryContainer = Color(0xFFDDE0FF),

    tertiary = Color(0xFFCE93D8),
    tertiaryContainer = Color(0xFF3D2844),

    error = Color(0xFFEF5350),
    onError = Color.White,

    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
)

@Composable
fun VPN4TVTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvDarkColors) {
        Surface(color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
