package com.qrz.voicetriggerrecorder.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = NightBlue,
    onPrimary = WarmSand,
    primaryContainer = NightBlueLight,
    onPrimaryContainer = NightBlue,
    secondary = MoonTeal,
    onSecondary = WarmSand,
    secondaryContainer = MoonTealLight,
    onSecondaryContainer = MoonTeal,
    background = WarmSand,
    onBackground = DeepInk,
    surface = ColorTokens.surfaceLight,
    onSurface = DeepInk,
    surfaceVariant = Mist,
    onSurfaceVariant = SoftSlate,
    error = WarningAmber
)

private val DarkColors = darkColorScheme(
    primary = NightBlueLight,
    onPrimary = NightBlue,
    primaryContainer = NightBlue,
    onPrimaryContainer = WarmSand,
    secondary = MoonTealLight,
    onSecondary = MoonTeal,
    secondaryContainer = MoonTeal,
    onSecondaryContainer = WarmSand,
    background = DeepInk,
    onBackground = WarmSand,
    surface = MistDark,
    onSurface = WarmSand,
    surfaceVariant = ColorTokens.surfaceVariantDark,
    onSurfaceVariant = SoftSlateDark,
    error = WarningAmber
)

private object ColorTokens {
    val surfaceLight = Color(0xFFFFFCF6)
    val surfaceVariantDark = Color(0xFF2A3342)
}

@Composable
fun VoiceRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
