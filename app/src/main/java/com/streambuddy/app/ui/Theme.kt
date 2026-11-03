package com.streambuddy.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Stream Buddy is deliberately dark-only: media apps look best on near-black, and the platform
 * window background is set to the same colour in res/values/themes.xml.
 */
private val StreamBuddyColors = darkColorScheme(
    primary = Color(0xFF4FA3FF),
    onPrimary = Color(0xFF00203F),
    primaryContainer = Color(0xFF16324F),
    onPrimaryContainer = Color(0xFFD6E9FF),
    secondary = Color(0xFF6FD3A8),
    onSecondary = Color(0xFF00301F),
    secondaryContainer = Color(0xFF15352A),
    onSecondaryContainer = Color(0xFFC7F5E0),
    tertiary = Color(0xFFF0B429),
    onTertiary = Color(0xFF3D2B00),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B222C),
    onSurfaceVariant = Color(0xFF9BA7B4),
    outline = Color(0xFF3A4552),
    outlineVariant = Color(0xFF252E39),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF3D0004),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun StreamBuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StreamBuddyColors, content = content)
}