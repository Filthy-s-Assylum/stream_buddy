package com.streambuddy.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StreamBuddyColors = darkColorScheme(
    primary = Color(0xFF4CC9F0),
    onPrimary = Color(0xFF04121A),
    primaryContainer = Color(0xFF0B3C4D),
    onPrimaryContainer = Color(0xFFB9ECFF),
    secondary = Color(0xFFB388FF),
    onSecondary = Color(0xFF16052B),
    tertiary = Color(0xFF64F0B0),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF9DA7B3),
    outline = Color(0xFF3D444D),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF2B0704),
    errorContainer = Color(0xFF4A1110),
    onErrorContainer = Color(0xFFFFD7D4),
)

@Composable
fun StreamBuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StreamBuddyColors,
        typography = Typography(),
        content = content,
    )
}
