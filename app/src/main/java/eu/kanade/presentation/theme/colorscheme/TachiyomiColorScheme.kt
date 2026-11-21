package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Grove: forest-green, heavily tinted surfaces shared with the other Grove apps.
 */
internal object TachiyomiColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFF339966),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF1E5C3D),
        onPrimaryContainer = Color(0xFFB9F0CF),
        inversePrimary = Color(0xFF2E7D5B),
        secondary = Color(0xFF339966),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF2A5A45),
        onSecondaryContainer = Color(0xFFCDEFDA),
        tertiary = Color(0xFFE2C38A),
        onTertiary = Color(0xFF3F2D00),
        tertiaryContainer = Color(0xFF5B4316),
        onTertiaryContainer = Color(0xFFFFDFA6),
        background = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF1F1F1F),
        onSurfaceVariant = Color(0xFF9A9A9A),
        surfaceTint = Color(0xFF000000),
        inverseSurface = Color(0xFFDDEDE3),
        inverseOnSurface = Color(0xFF0E1D16),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF7E978A),
        outlineVariant = Color(0xFF333333),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0D0D0D),
        surfaceContainer = Color(0xFF141414),
        surfaceContainerHigh = Color(0xFF1F1F1F),
        surfaceContainerHighest = Color(0xFF2A2A2A),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFF2E7D5B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB9F0CF),
        onPrimaryContainer = Color(0xFF002113),
        inversePrimary = Color(0xFF7FD3A4),
        secondary = Color(0xFF2E7D5B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCDEFDA),
        onSecondaryContainer = Color(0xFF0B2A1C),
        tertiary = Color(0xFF7A5A1E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDFA6),
        onTertiaryContainer = Color(0xFF261A00),
        background = Color(0xFFF1F8F3),
        onBackground = Color(0xFF14201A),
        surface = Color(0xFFF1F8F3),
        onSurface = Color(0xFF14201A),
        surfaceVariant = Color(0xFFDDEDE3),
        onSurfaceVariant = Color(0xFF3F5247),
        surfaceTint = Color(0xFF2E7D5B),
        inverseSurface = Color(0xFF29362F),
        inverseOnSurface = Color(0xFFE8F5EC),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF6F8378),
        outlineVariant = Color(0xFFBFD3C6),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFE9F3EC),
        surfaceContainer = Color(0xFFE2EFE6),
        surfaceContainerHigh = Color(0xFFDAEADF),
        surfaceContainerHighest = Color(0xFFD2E4D8),
    )
}
