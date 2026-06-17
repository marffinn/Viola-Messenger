package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = SleekAccent,
    secondary = SleekAccent,
    tertiary = SleekAccent,
    background = SleekDark,
    surface = SleekSurface,
    onPrimary = SleekContrastText,
    onSecondary = SleekContrastText,
    onTertiary = SleekContrastText,
    onBackground = SleekTextPrimary,
    onSurface = SleekTextPrimary,
    surfaceVariant = SleekCard,
    onSurfaceVariant = SleekTextSecondary,
    outline = SleekBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark theme
    dynamicColor: Boolean = false, // Disable dynamic color to stick to our beautiful palette
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
