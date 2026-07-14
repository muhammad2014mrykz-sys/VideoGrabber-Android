package com.videograbber.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF3B7BFF)
val BrandLight = Color(0xFF4C8DFF)
val BgDark = Color(0xFF0F1420)
val SurfaceDark = Color(0xFF161D2B)
val CardDark = Color(0xFF1A2130)
val OnDarkMuted = Color(0xFF8B97AD)

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    background = BgDark,
    onBackground = Color(0xFFE6EBF5),
    surface = SurfaceDark,
    onSurface = Color(0xFFE6EBF5),
    surfaceVariant = CardDark,
    onSurfaceVariant = OnDarkMuted,
    outline = Color(0xFF2A3448),
)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
)

@Composable
fun VideoGrabberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The app is designed dark-first (matches the desktop version).
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else DarkColors,
        content = content,
    )
}
