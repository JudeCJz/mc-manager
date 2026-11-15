package com.mcmanager.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mcmanager.core.data.settings.ThemeMode

// Minecraft-ish grass green seed.
private val Green10 = Color(0xFF0B2000)
private val Green40 = Color(0xFF3C7D22)
private val Green80 = Color(0xFF8FD96F)
private val Green90 = Color(0xFFB0F68E)

private val LightColors = lightColorScheme(
  primary = Green40,
  onPrimary = Color.White,
  primaryContainer = Green90,
  onPrimaryContainer = Green10,
  secondary = Color(0xFF8B5A2B),
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFFFDCBE),
  onSecondaryContainer = Color(0xFF2D1600),
  tertiary = Color(0xFF3A6A78),
  background = Color(0xFFFAFDF4),
  surface = Color(0xFFFAFDF4),
)

private val DarkColors = darkColorScheme(
  primary = Green80,
  onPrimary = Color(0xFF133800),
  primaryContainer = Color(0xFF265100),
  onPrimaryContainer = Green90,
  secondary = Color(0xFFE5BF98),
  onSecondary = Color(0xFF442B0E),
  secondaryContainer = Color(0xFF5E4122),
  onSecondaryContainer = Color(0xFFFFDCBE),
  tertiary = Color(0xFFA2CEDD),
  background = Color(0xFF121412),
  surface = Color(0xFF121412),
)

private fun ColorScheme.toAmoled(): ColorScheme = copy(
  background = Color.Black,
  surface = Color.Black,
  surfaceDim = Color.Black,
  surfaceContainerLowest = Color.Black,
  surfaceContainerLow = Color(0xFF0A0A0A),
  surfaceContainer = Color(0xFF101010),
  surfaceContainerHigh = Color(0xFF161616),
  surfaceContainerHighest = Color(0xFF1C1C1C),
)

@Composable
fun MCManagerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
  val dark = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
  }
  val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
  val context = LocalContext.current
  val base = when {
    dynamic && dark -> dynamicDarkColorScheme(context)
    dynamic -> dynamicLightColorScheme(context)
    dark -> DarkColors
    else -> LightColors
  }
  val scheme = if (mode == ThemeMode.AMOLED) base.toAmoled() else base
  MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
