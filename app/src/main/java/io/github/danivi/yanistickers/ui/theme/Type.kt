package io.github.danivi.yanistickers.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.danivi.yanistickers.R

/**
 * Fuente principal Vanilla Caramel; Android realiza automáticamente el fallback a la tipografía
 * del sistema para glifos no incluidos (por ejemplo, emojis o símbolos especiales).
 */
val VanillaCaramelFontFamily = FontFamily(
    Font(R.font.vanilla_caramel, weight = FontWeight.Normal),
    Font(R.font.vanilla_caramel, weight = FontWeight.Medium),
    Font(R.font.vanilla_caramel, weight = FontWeight.SemiBold),
    Font(R.font.vanilla_caramel, weight = FontWeight.Bold),
)

private val BaseTypography = Typography()

val Typography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = VanillaCaramelFontFamily),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = VanillaCaramelFontFamily),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = VanillaCaramelFontFamily),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = VanillaCaramelFontFamily),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = VanillaCaramelFontFamily),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = VanillaCaramelFontFamily),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = VanillaCaramelFontFamily),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = VanillaCaramelFontFamily),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = VanillaCaramelFontFamily),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = VanillaCaramelFontFamily),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = VanillaCaramelFontFamily),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = VanillaCaramelFontFamily),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = VanillaCaramelFontFamily),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = VanillaCaramelFontFamily),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = VanillaCaramelFontFamily),
)
