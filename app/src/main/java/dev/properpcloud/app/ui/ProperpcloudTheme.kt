package dev.properpcloud.app.ui

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF4B3F72),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF211A3A),
    secondary = Color(0xFF006A6A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9CF1F0),
    tertiary = Color(0xFF8B4A00),
    tertiaryContainer = Color(0xFFFFDCC1),
    surface = Color(0xFFFEF8FF),
    surfaceVariant = Color(0xFFE8E0EB),
    background = Color(0xFFFEF8FF),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCEBDFF),
    onPrimary = Color(0xFF33265A),
    primaryContainer = Color(0xFF4B3F72),
    secondary = Color(0xFF80D5D4),
    onSecondary = Color(0xFF003737),
    tertiary = Color(0xFFFFB776),
    onTertiary = Color(0xFF4A2800),
    surface = Color(0xFF151218),
    background = Color(0xFF151218),
    error = Color(0xFFFFB4AB),
)

@Composable
fun ProperpcloudTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
