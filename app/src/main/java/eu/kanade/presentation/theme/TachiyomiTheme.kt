package eu.kanade.presentation.theme

import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.presentation.theme.colorscheme.CatppuccinColorScheme
import eu.kanade.presentation.theme.colorscheme.GreenAppleColorScheme
import eu.kanade.presentation.theme.colorscheme.LavenderColorScheme
import eu.kanade.presentation.theme.colorscheme.MidnightDuskColorScheme
import eu.kanade.presentation.theme.colorscheme.MonetColorScheme
import eu.kanade.presentation.theme.colorscheme.MonochromeColorScheme
import eu.kanade.presentation.theme.colorscheme.NordColorScheme
import eu.kanade.presentation.theme.colorscheme.StrawberryColorScheme
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme
import eu.kanade.presentation.theme.colorscheme.TakoColorScheme
import eu.kanade.presentation.theme.colorscheme.TealTurqoiseColorScheme
import eu.kanade.presentation.theme.colorscheme.TidalWaveColorScheme
import eu.kanade.presentation.theme.colorscheme.TokyoNightColorScheme
import eu.kanade.presentation.theme.colorscheme.YinYangColorScheme
import eu.kanade.presentation.theme.colorscheme.YotsubaColorScheme
import mihon.app.di.appGraph

@Composable
fun TachiyomiTheme(
    appTheme: AppTheme? = null,
    amoled: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val uiPreferences = remember { context.appGraph.uiPreferences }
    BaseTachiyomiTheme(
        appTheme = appTheme ?: uiPreferences.appTheme.get(),
        isAmoled = amoled ?: uiPreferences.themeDarkAmoled.get(),
        content = content,
    )
}

@Composable
fun TachiyomiPreviewTheme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    isAmoled: Boolean = false,
    content: @Composable () -> Unit,
) = BaseTachiyomiTheme(appTheme, isAmoled, content)

@Composable
private fun BaseTachiyomiTheme(
    appTheme: AppTheme,
    isAmoled: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Grovebook: Windows Phone dark theme, always
    val isDark = true
    MaterialExpressiveTheme(
        shapes = MetroShapes,
        typography = MetroTypography,
        colorScheme = remember(appTheme, isDark, isAmoled) {
            getThemeColorScheme(
                context = context,
                appTheme = appTheme,
                isDark = isDark,
                isAmoled = isAmoled,
            )
        },
        content = content,
    )
}

private fun getThemeColorScheme(
    context: Context,
    appTheme: AppTheme,
    isDark: Boolean,
    isAmoled: Boolean,
): ColorScheme {
    val colorScheme = if (appTheme == AppTheme.MONET) {
        MonetColorScheme(context)
    } else {
        colorSchemes.getOrDefault(appTheme, TachiyomiColorScheme)
    }
    val base = colorScheme.getColorScheme(
        isDark = isDark,
        isAmoled = isAmoled,
        overrideDarkSurfaceContainers = appTheme != AppTheme.MONET,
    )
    // GroveMedia: books use the same accent colour and pure-black, accent-tinted surfaces as music & video
    val accent = androidx.compose.ui.graphics.Color(com.grove.music.Prefs(context).accent)
    val black = androidx.compose.ui.graphics.Color.Black
    val white = androidx.compose.ui.graphics.Color.White
    fun tint(f: Float) = androidx.compose.ui.graphics.lerp(black, accent, f)
    return base.copy(
        primary = accent, onPrimary = white, primaryContainer = tint(0.55f), onPrimaryContainer = white,
        secondary = accent, onSecondary = white, secondaryContainer = tint(0.35f), onSecondaryContainer = white,
        tertiary = accent, onTertiary = white, tertiaryContainer = tint(0.35f), onTertiaryContainer = white,
        background = black, onBackground = white, surface = black, onSurface = white,
        surfaceVariant = tint(0.12f), onSurfaceVariant = white.copy(alpha = 0.75f),
        surfaceContainerLowest = black, surfaceContainerLow = tint(0.06f), surfaceContainer = tint(0.09f),
        surfaceContainerHigh = tint(0.13f), surfaceContainerHighest = tint(0.17f), surfaceTint = accent,
        inversePrimary = accent, outline = white.copy(alpha = 0.35f), outlineVariant = white.copy(alpha = 0.15f),
    )
}

private val colorSchemes: Map<AppTheme, BaseColorScheme> = mapOf(
    AppTheme.DEFAULT to TachiyomiColorScheme,
    AppTheme.CATPPUCCIN to CatppuccinColorScheme,
    AppTheme.TOKYONIGHT to TokyoNightColorScheme,
    AppTheme.GREEN_APPLE to GreenAppleColorScheme,
    AppTheme.LAVENDER to LavenderColorScheme,
    AppTheme.MIDNIGHT_DUSK to MidnightDuskColorScheme,
    AppTheme.MONOCHROME to MonochromeColorScheme,
    AppTheme.NORD to NordColorScheme,
    AppTheme.STRAWBERRY_DAIQUIRI to StrawberryColorScheme,
    AppTheme.TAKO to TakoColorScheme,
    AppTheme.TEALTURQUOISE to TealTurqoiseColorScheme,
    AppTheme.TIDAL_WAVE to TidalWaveColorScheme,
    AppTheme.YINYANG to YinYangColorScheme,
    AppTheme.YOTSUBA to YotsubaColorScheme,
)

// Metro is square and light-weighted
private val MetroCorner = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
private val MetroShapes = androidx.compose.material3.Shapes(
    extraSmall = MetroCorner,
    small = MetroCorner,
    medium = MetroCorner,
    large = MetroCorner,
    extraLarge = MetroCorner,
)
private val MetroTypography = androidx.compose.material3.Typography().let { t ->
    val light = androidx.compose.ui.text.font.FontWeight.Light
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = light),
        displayMedium = t.displayMedium.copy(fontWeight = light),
        displaySmall = t.displaySmall.copy(fontWeight = light),
        headlineLarge = t.headlineLarge.copy(fontWeight = light),
        headlineMedium = t.headlineMedium.copy(fontWeight = light),
        headlineSmall = t.headlineSmall.copy(fontWeight = light),
        titleLarge = t.titleLarge.copy(fontWeight = light, fontSize = 24.sp),
        titleMedium = t.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
        bodyLarge = t.bodyLarge.copy(fontSize = 18.sp),
    )
}
