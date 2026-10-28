package com.dextray.materialprinter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val PaperLightColorScheme = lightColorScheme(
    primary = InkPrimary,
    onPrimary = InkOnPrimary,
    primaryContainer = InkPrimaryContainer,
    onPrimaryContainer = InkOnPrimaryContainer,
    secondary = InkSecondary,
    onSecondary = InkOnSecondary,
    secondaryContainer = InkSecondaryContainer,
    onSecondaryContainer = InkOnSecondaryContainer,
    tertiary = HeatTertiary,
    onTertiary = HeatOnTertiary,
    tertiaryContainer = HeatTertiaryContainer,
    onTertiaryContainer = HeatOnTertiaryContainer,
    background = PaperBackground,
    onBackground = PaperOnBackground,
    surface = PaperSurface,
    onSurface = PaperOnBackground,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = PaperOnSurfaceVariant,
    outline = PaperOutline,
    outlineVariant = PaperOutlineVariant,
    surfaceContainerLowest = PaperContainerLowest,
    surfaceContainerLow = PaperContainerLow,
    surfaceContainer = PaperContainer,
    surfaceContainerHigh = PaperContainerHigh,
    surfaceContainerHighest = PaperContainerHighest
)

private val CaseDarkColorScheme = darkColorScheme(
    primary = InkDarkPrimary,
    onPrimary = InkDarkOnPrimary,
    primaryContainer = InkDarkPrimaryContainer,
    onPrimaryContainer = InkDarkOnPrimaryContainer,
    secondary = InkDarkSecondary,
    onSecondary = InkDarkOnSecondary,
    secondaryContainer = InkDarkSecondaryContainer,
    onSecondaryContainer = InkDarkOnSecondaryContainer,
    tertiary = HeatDarkTertiary,
    onTertiary = HeatDarkOnTertiary,
    tertiaryContainer = HeatDarkTertiaryContainer,
    onTertiaryContainer = HeatDarkOnTertiaryContainer,
    background = CaseBackground,
    onBackground = CaseOnBackground,
    surface = CaseSurface,
    onSurface = CaseOnBackground,
    surfaceVariant = CaseSurfaceVariant,
    onSurfaceVariant = CaseOnSurfaceVariant,
    outline = CaseOutline,
    outlineVariant = CaseOutlineVariant,
    surfaceContainerLowest = CaseContainerLowest,
    surfaceContainerLow = CaseContainerLow,
    surfaceContainer = CaseContainer,
    surfaceContainerHigh = CaseContainerHigh,
    surfaceContainerHighest = CaseContainerHighest
)

// Soft, generous corners; a hierarchy instead of one radius for everything
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun MaterialPrinterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Set to true to take colours from the wallpaper (Android 12+, "Material You")
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> CaseDarkColorScheme
        else -> PaperLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
