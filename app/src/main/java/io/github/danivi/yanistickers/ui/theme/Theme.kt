package io.github.danivi.yanistickers.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = PureWhite,
    primaryContainer = PrimaryBlueContainer,
    onPrimaryContainer = OnPrimaryBlueContainer,
    secondary = SecondaryBlue,
    onSecondary = PureWhite,
    secondaryContainer = SecondaryBlueContainer,
    onSecondaryContainer = OnSecondaryBlueContainer,
    tertiary = TertiaryBlue,
    onTertiary = PureWhite,
    tertiaryContainer = TertiaryBlueContainer,
    onTertiaryContainer = OnTertiaryBlueContainer,
    error = VibrantRed,
    onError = PureWhite,
    errorContainer = VibrantRedContainer,
    onErrorContainer = OnVibrantRedContainer,
    background = PureWhite,
    onBackground = LightOnSurface,
    surface = PureWhite,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

@Composable
fun YaniStickersTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = false,
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Se fuerza el esquema claro con fondo blanco para mantener coherencia visual e impedir tonos dinámicos oscuros o marrones.
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content,
    )
}
