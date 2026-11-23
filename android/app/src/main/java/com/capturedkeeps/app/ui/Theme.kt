package com.capturedkeeps.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

// Tablet in an event room: large, readable sizes, scaled down on smaller screens.
private fun type(f: Float) = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 56.sp * f, letterSpacing = 1.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp * f),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 24.sp * f),
    bodyLarge = TextStyle(fontSize = 20.sp * f, lineHeight = 28.sp * f),
    bodyMedium = TextStyle(fontSize = 17.sp * f, lineHeight = 24.sp * f),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp * f, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontSize = 14.sp * f, letterSpacing = 4.sp * f),
)

/** 1 on a big tablet (shorter side ≥ 800 dp), down to 0.7 on a phone. Text and big spacing use it. */
val LocalUiScale = staticCompositionLocalOf { 1f }

@Composable
fun Dp.scaled(): Dp = this * LocalUiScale.current

@Composable
fun CapturedKeepsTheme(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val scale = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) / 800f).coerceIn(0.7f, 1f)
    CompositionLocalProvider(LocalUiScale provides scale) {
        MaterialTheme(colorScheme = Colors, typography = type(scale), content = content)
    }
}

/**
 * Two panes: side by side when the screen is wide, stacked when it's tall (portrait).
 * [equal] = both panes share the space; otherwise [first] (a strip) takes what it needs and [second] the rest.
 */
@Composable
fun SplitPane(
    modifier: Modifier = Modifier,
    equal: Boolean = false,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) = BoxWithConstraints(modifier.fillMaxSize()) {
    val gap = 32.dp.scaled()
    if (maxWidth >= maxHeight) Row(Modifier.fillMaxSize().padding(gap), horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically) {
        first(if (equal) Modifier.weight(1f).fillMaxHeight() else Modifier.fillMaxHeight())
        second(Modifier.weight(1f).let { if (equal) it.fillMaxHeight() else it })
    } else Column(Modifier.fillMaxSize().padding(gap), verticalArrangement = Arrangement.spacedBy(gap),
        horizontalAlignment = Alignment.CenterHorizontally) {
        // Stacked: a strip takes the free height; for two text panes the top one takes only what it needs.
        first(if (equal) Modifier.fillMaxWidth() else Modifier.weight(1f))
        second(if (equal) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth())
    }
}
