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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
                if (Device.isTv) TvMaterialTheme(content) else content()
            }
        }
    }
}

/**
 * TV: Compose for TV's own theme (androidx.tv.material3) under the TV screens,
 * whose components read it, not Material 3's (Google: "each library has its
 * own MaterialTheme object"). Its colours are built from the same dark scheme,
 * so the two cannot drift apart; the type scale is the TV's ([TvTypography]).
 */
@Composable
private fun TvMaterialTheme(content: @Composable () -> Unit) {
    val m3 = MaterialTheme.colorScheme
    val colors = remember(m3) {
        androidx.tv.material3.darkColorScheme(
            primary = m3.primary,
            onPrimary = m3.onPrimary,
            primaryContainer = m3.primaryContainer,
            onPrimaryContainer = m3.onPrimaryContainer,
            inversePrimary = m3.inversePrimary,
            secondary = m3.secondary,
            onSecondary = m3.onSecondary,
            secondaryContainer = m3.secondaryContainer,
            onSecondaryContainer = m3.onSecondaryContainer,
            tertiary = m3.tertiary,
            onTertiary = m3.onTertiary,
            tertiaryContainer = m3.tertiaryContainer,
            onTertiaryContainer = m3.onTertiaryContainer,
            background = m3.background,
            onBackground = m3.onBackground,
            surface = m3.surface,
            onSurface = m3.onSurface,
            // The plate of a focused row, a card or a code: the TV screens' grey.
            surfaceVariant = m3.surfaceContainerHigh,
            onSurfaceVariant = m3.onSurfaceVariant,
            surfaceTint = m3.surfaceTint,
            inverseSurface = m3.inverseSurface,
            inverseOnSurface = m3.inverseOnSurface,
            error = m3.error,
            onError = m3.onError,
            errorContainer = m3.errorContainer,
            onErrorContainer = m3.onErrorContainer,
            border = m3.outline,
            borderVariant = m3.outlineVariant,
            scrim = m3.scrim,
        )
    }
    androidx.tv.material3.MaterialTheme(colorScheme = colors, typography = TvTypography) {
        // Text outside any TV surface: the screens' light grey, not the library's black.
        CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides colors.onBackground, content = content)
    }
}

/** The grey of a control that is busy (Connect while connecting), lighter than [TvMaterialTheme]'s plate. */
val TvBusyContainer: Color = DarkColors.surfaceContainerHighest

/**
 * The TV type scale: Material's roles at the sizes the TV screens are read at
 * from the sofa (Google's TV typography: "Prioritize using larger
 * typography"), so a screen names a role, not a size. Lists: titleMedium for
 * a row, bodySmall under it; buttons: labelLarge.
 */
private val TvTypography = androidx.tv.material3.Typography().let { d ->
    d.copy(
        displayMedium = d.displayMedium.copy(fontSize = 40.sp, lineHeight = 48.sp),
        displaySmall = d.displaySmall.copy(fontSize = 36.sp, lineHeight = 44.sp),
        headlineLarge = d.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium = d.headlineMedium.copy(fontSize = 30.sp, lineHeight = 38.sp),
        headlineSmall = d.headlineSmall.copy(fontSize = 28.sp, lineHeight = 36.sp),
        titleLarge = d.titleLarge.copy(fontSize = 24.sp, lineHeight = 32.sp),
        titleMedium = d.titleMedium.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
        titleSmall = d.titleSmall.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
        bodyLarge = d.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodyMedium = d.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodySmall = d.bodySmall.copy(fontSize = 15.sp, lineHeight = 21.sp),
        labelLarge = d.labelLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
        labelMedium = d.labelMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
        // Small print (a key's term, an error's detail), not a control's label.
        labelSmall = d.labelSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    )
}
