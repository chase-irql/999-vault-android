package com.vault999.android.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object VaultColors {
    val Canvas = Color(0xFF080B0F)
    val Chrome = Color(0xFF05070A)
    val Surface = Color(0xFF101720)
    val SurfaceRaised = Color(0xFF16212C)
    val Cyan = Color(0xFF44C7F3)
    val Yellow = Color(0xFFEEF234)
    val Red = Color(0xFFEF4050)
    val Green = Color(0xFF52D28A)
    val Muted = Color(0xFFA8B5C2)
}

private val VaultColorScheme: ColorScheme = darkColorScheme(
    primary = VaultColors.Yellow,
    onPrimary = VaultColors.Canvas,
    secondary = VaultColors.Cyan,
    onSecondary = VaultColors.Canvas,
    tertiary = VaultColors.Green,
    background = VaultColors.Canvas,
    onBackground = Color(0xFFF5F8FA),
    surface = VaultColors.Surface,
    onSurface = Color(0xFFF5F8FA),
    surfaceVariant = VaultColors.SurfaceRaised,
    onSurfaceVariant = VaultColors.Muted,
    error = VaultColors.Red,
)

private val VaultTypography = androidx.compose.material3.Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 38.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
)

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = VaultColorScheme,
        typography = VaultTypography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = CutCornerShape(2.dp),
            small = CutCornerShape(topEnd = 8.dp),
            medium = CutCornerShape(topEnd = 12.dp),
            large = CutCornerShape(topEnd = 18.dp),
        ),
        content = content,
    )
}

