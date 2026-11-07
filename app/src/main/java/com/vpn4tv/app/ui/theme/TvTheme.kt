package com.vpn4tv.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.vpn4tv.app.ui.Device

// Material3 dark scheme with purple seed matching VPN4TV logo
private val DarkColors = darkColorScheme(
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
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF252525),
    surfaceContainerHighest = Color(0xFF2E2E2E),

    secondary = Color(0xFF9FA8DA),
    secondaryContainer = Color(0xFF2E3556),
    onSecondaryContainer = Color(0xFFDDE0FF),

    tertiary = Color(0xFFCE93D8),
    tertiaryContainer = Color(0xFF3D2844),

    error = Color(0xFFEF5350),
    onError = Color.White,

    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFF3A3A3A),
)

// The same purple seed for a light system theme on phones without dynamic color.
private val LightColors = lightColorScheme(
    primary = Color(0xFF5C3BBF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF1D0060),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE0E0F9),
    onSecondaryContainer = Color(0xFF181A2C),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

/** Colours that mean a state, readable on both themes. */
@Immutable
data class StateColors(
    val connected: Color,
    val busy: Color,
    val fast: Color,
    val medium: Color,
    val slow: Color,
    val danger: Color,
)

private val DarkStateColors = StateColors(
    connected = Color(0xFF5BD47A),
    busy = Color(0xFFFFD54F),
    fast = Color(0xFF5BD47A),
    medium = Color(0xFFFFD54F),
    slow = Color(0xFFFF8A50),
    danger = Color(0xFFFF6E6A),
)

private val LightStateColors = StateColors(
    connected = Color(0xFF1B7F3A),
    busy = Color(0xFF8A6100),
    fast = Color(0xFF1B7F3A),
    medium = Color(0xFF8A6100),
    slow = Color(0xFFB0420B),
    danger = Color(0xFFBA1A1A),
)

val LocalStateColors = staticCompositionLocalOf { DarkStateColors }

/**
 * TV: always dark (the TV colour guide: build from dark themes).
 * Phone: follows the system light/dark setting, with the wallpaper's dynamic
 * colours on Android 12+ and our purple scheme as the fallback, as the
 * Android mobile themes guide asks.
 */
@Composable
fun VPN4TVTheme(content: @Composable () -> Unit) {
    val dark = Device.isTv || isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Device.isTv -> DarkColors
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalStateColors provides if (dark) DarkStateColors else LightStateColors) {
        MaterialTheme(colorScheme = scheme) {
            Surface(color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }
}
