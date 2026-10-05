package com.codex.quota.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.codex.quota.domain.model.AppThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6AA5FF),
    onPrimary = Slate950,
    primaryContainer = Color(0xFF164078),
    onPrimaryContainer = Emerald50,
    secondary = Cyan400,
    onSecondary = Slate950,
    secondaryContainer = Cyan700,
    onSecondaryContainer = Color.White,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    surfaceDim = Slate950,
    surfaceBright = Slate700,
    surfaceContainerLowest = Slate950,
    surfaceContainerLow = Slate900,
    surfaceContainer = Color(0xFF172033),
    surfaceContainerHigh = Slate800,
    surfaceContainerHighest = Slate700,
    onSurfaceVariant = Slate400,
    outline = Slate700,
    outlineVariant = Color(0xFF2B364B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0868E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F1FF),
    onPrimaryContainer = Color(0xFF134F9C),
    secondary = Cyan500,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Cyan700,
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    surfaceDim = Slate200,
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Slate50,
    surfaceContainer = Slate100,
    surfaceContainerHigh = Color(0xFFEAF0F6),
    surfaceContainerHighest = Slate200,
    onSurfaceVariant = Slate600,
    outline = Slate200,
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

@Composable
fun CodexQuotaTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    // Wallpaper accents still color actions, but never tint reading surfaces.
    val colorScheme = if (darkTheme) baseScheme.copy(
        background = Color(0xFF121212), surface = Color(0xFF1B1B1B), surfaceTint = Color.Transparent,
        surfaceDim = Color(0xFF121212), surfaceBright = Color(0xFF333333),
        surfaceContainerLowest = Color(0xFF1B1B1B), surfaceContainerLow = Color(0xFF202020),
        surfaceContainer = Color(0xFF262626), surfaceContainerHigh = Color(0xFF2C2C2C),
        surfaceContainerHighest = Color(0xFF333333), surfaceVariant = Color(0xFF262626),
        onSurface = Color(0xFFF2F2F2), onBackground = Color(0xFFF2F2F2),
        onSurfaceVariant = Color(0xFFB4B4B4), outline = Color(0xFF777777), outlineVariant = Color(0xFF363636)
    ) else baseScheme.copy(
        background = Color(0xFFF7F7F7), surface = Color.White, surfaceTint = Color.Transparent,
        surfaceDim = Color(0xFFEEEEEE), surfaceBright = Color.White,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFA),
        surfaceContainer = Color(0xFFEEEEEE), surfaceContainerHigh = Color(0xFFE8E8E8),
        surfaceContainerHighest = Color(0xFFE2E2E2), surfaceVariant = Color(0xFFEEEEEE),
        onSurface = Color(0xFF171717), onBackground = Color(0xFF171717),
        onSurfaceVariant = Color(0xFF626262), outline = Color(0xFF858585), outlineVariant = Color(0xFFE2E2E2)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
