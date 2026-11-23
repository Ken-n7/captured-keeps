package com.capturedkeeps.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Brand: warm ivory, charcoal type, muted beige/taupe accents.
object Brand {
    val Ivory = Color(0xFFF7F3EC)
    val OffWhite = Color(0xFFFFFDF9)
    val Charcoal = Color(0xFF2B2B2B)
    val Beige = Color(0xFFE6DCCB)
    val Taupe = Color(0xFF8B7D6B)
    val Muted = Color(0xFF6B6459)

    // Status colors (event state, device health, payment, sync)
    val Ok = Color(0xFF4F7A55)
    val Warn = Color(0xFFB7862F)
    val Error = Color(0xFFA94438)
}

private val Colors = lightColorScheme(
    primary = Brand.Charcoal,
    onPrimary = Brand.OffWhite,
    secondary = Brand.Taupe,
    onSecondary = Brand.OffWhite,
    secondaryContainer = Brand.Beige,
    onSecondaryContainer = Brand.Charcoal,
    background = Brand.Ivory,
    onBackground = Brand.Charcoal,
    surface = Brand.OffWhite,
    onSurface = Brand.Charcoal,
    onSurfaceVariant = Brand.Muted,
    outline = Brand.Taupe,
    error = Brand.Error,
)

// Tablet in an event room: large, readable sizes.
private val Type = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 56.sp, letterSpacing = 1.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 24.sp),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontSize = 14.sp, letterSpacing = 4.sp),
)

@Composable
fun CapturedKeepsTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
