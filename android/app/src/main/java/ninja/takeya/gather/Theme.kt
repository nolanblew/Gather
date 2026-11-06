package ninja.takeya.gather

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Paper =
    lightColorScheme(
        primary = Color(0xFF43634D),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE1EADB),
        onPrimaryContainer = Color(0xFF24382B),
        secondary = Color(0xFF71674D),
        secondaryContainer = Color(0xFFF1E8D2),
        background = Color(0xFFF7F5EE),
        onBackground = Color(0xFF26342C),
        surface = Color(0xFFFFFEF9),
        onSurface = Color(0xFF26342C),
        surfaceVariant = Color(0xFFECEDE3),
        onSurfaceVariant = Color(0xFF637064),
        outline = Color(0xFF7D887A),
        outlineVariant = Color(0xFFDFE2D6),
        error = Color(0xFFAA3434),
    )
private val Forest =
    darkColorScheme(
        primary = Color(0xFFB3D0AF),
        onPrimary = Color(0xFF203923),
        primaryContainer = Color(0xFF344D38),
        onPrimaryContainer = Color(0xFFDBECD4),
        secondary = Color(0xFFD5C8A6),
        secondaryContainer = Color(0xFF4E4938),
        background = Color(0xFF171F1B),
        onBackground = Color(0xFFE3E9DD),
        surface = Color(0xFF202A23),
        onSurface = Color(0xFFE3E9DD),
        surfaceVariant = Color(0xFF303B31),
        onSurfaceVariant = Color(0xFFB8C4B4),
        outline = Color(0xFF8E9A89),
        outlineVariant = Color(0xFF424E41),
    )

@Composable
fun GatherTheme(appearance: String, content: @Composable () -> Unit) {
    val dark =
        appearance in listOf("dark", "midnight") ||
            (appearance == "system" && isSystemInDarkTheme())
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val colors =
        when (appearance) {
            "cloud" ->
                Paper.copy(
                    background = Color(0xFFF0F4F6),
                    surface = Color(0xFFFDFEFF),
                    surfaceVariant = Color(0xFFE5ECEF),
                )
            "midnight" ->
                Forest.copy(
                    background = Color(0xFF141E2C),
                    surface = Color(0xFF1D2A3B),
                    surfaceVariant = Color(0xFF2C3A4C),
                    outlineVariant = Color(0xFF3B4B5E),
                )
            else -> if (dark) Forest else Paper
        }
    MaterialTheme(
        colorScheme = animatedGatherColors(colors),
        typography =
            Typography(
                displaySmall =
                    TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 36.sp,
                        lineHeight = 42.sp,
                    ),
                headlineLarge =
                    TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 38.sp),
                headlineMedium =
                    TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
                titleLarge =
                    TextStyle(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 21.sp,
                        lineHeight = 28.sp,
                    ),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            ),
        content = content,
    )
}

@Composable
private fun animatedGatherColors(target: ColorScheme): ColorScheme {
    @Composable
    fun color(value: Color): Color =
        animateColorAsState(value, GatherMotion.standard(), label = "Appearance color").value

    val colors =
        target.copy(
            primary = color(target.primary),
            onPrimary = color(target.onPrimary),
            primaryContainer = color(target.primaryContainer),
            onPrimaryContainer = color(target.onPrimaryContainer),
            inversePrimary = color(target.inversePrimary),
            secondary = color(target.secondary),
            onSecondary = color(target.onSecondary),
            secondaryContainer = color(target.secondaryContainer),
            onSecondaryContainer = color(target.onSecondaryContainer),
            tertiary = color(target.tertiary),
            onTertiary = color(target.onTertiary),
            tertiaryContainer = color(target.tertiaryContainer),
            onTertiaryContainer = color(target.onTertiaryContainer),
            background = color(target.background),
            onBackground = color(target.onBackground),
            surface = color(target.surface),
            onSurface = color(target.onSurface),
            surfaceVariant = color(target.surfaceVariant),
            onSurfaceVariant = color(target.onSurfaceVariant),
            surfaceTint = color(target.surfaceTint),
            inverseSurface = color(target.inverseSurface),
            inverseOnSurface = color(target.inverseOnSurface),
            error = color(target.error),
            onError = color(target.onError),
            errorContainer = color(target.errorContainer),
            onErrorContainer = color(target.onErrorContainer),
            outline = color(target.outline),
            outlineVariant = color(target.outlineVariant),
            scrim = color(target.scrim),
            surfaceBright = color(target.surfaceBright),
            surfaceDim = color(target.surfaceDim),
            surfaceContainer = color(target.surfaceContainer),
            surfaceContainerHigh = color(target.surfaceContainerHigh),
            surfaceContainerHighest = color(target.surfaceContainerHighest),
            surfaceContainerLow = color(target.surfaceContainerLow),
            surfaceContainerLowest = color(target.surfaceContainerLowest),
        )
    // Light/dark color interpolation crosses a gray midpoint. Keep paired text readable there.
    return colors.copy(
        onPrimary = readableOn(colors.primary, colors.onPrimary),
        onPrimaryContainer = readableOn(colors.primaryContainer, colors.onPrimaryContainer),
        onSecondary = readableOn(colors.secondary, colors.onSecondary),
        onSecondaryContainer = readableOn(colors.secondaryContainer, colors.onSecondaryContainer),
        onTertiary = readableOn(colors.tertiary, colors.onTertiary),
        onTertiaryContainer = readableOn(colors.tertiaryContainer, colors.onTertiaryContainer),
        onBackground = readableOn(colors.background, colors.onBackground),
        onSurface = readableOn(colors.surface, colors.onSurface),
        onSurfaceVariant = readableOn(colors.surfaceVariant, colors.onSurfaceVariant),
        inverseOnSurface = readableOn(colors.inverseSurface, colors.inverseOnSurface),
        onError = readableOn(colors.error, colors.onError),
        onErrorContainer = readableOn(colors.errorContainer, colors.onErrorContainer),
    )
}

private fun readableOn(background: Color, preferred: Color): Color {
    val backgroundLuminance = background.luminance()
    val preferredLuminance = preferred.luminance()
    val contrast =
        (maxOf(backgroundLuminance, preferredLuminance) + .05f) /
            (minOf(backgroundLuminance, preferredLuminance) + .05f)
    return if (contrast >= 4.5f) preferred
    else if (backgroundLuminance > .179f) Color.Black else Color.White
}

@Composable
fun tint(name: String): Color {
    val darkFraction =
        ((.85f - MaterialTheme.colorScheme.background.luminance()) / .8f).coerceIn(0f, 1f)
    return when (name) {
        "peach" -> lerp(Color(0xFFF8DFCD), Color(0xFF634637), darkFraction)
        "lavender" -> lerp(Color(0xFFE9E0F2), Color(0xFF4C435D), darkFraction)
        "sky" -> lerp(Color(0xFFDDEDF1), Color(0xFF34515C), darkFraction)
        "sand" -> lerp(Color(0xFFF1E7CA), Color(0xFF5B5137), darkFraction)
        "rose" -> lerp(Color(0xFFF4DFE3), Color(0xFF5F3F48), darkFraction)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
}
