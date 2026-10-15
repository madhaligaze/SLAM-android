package kz.slam.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Палитра веб-версии — чтобы бригадиры видели «то же приложение». */
object C {
    val bg = Color(0xFF0E151D)
    val bg2 = Color(0xFF0B1219)
    val card = Color(0xFF18232F)
    val card2 = Color(0xFF1F2D3B)
    val line = Color(0xFF2B3A49)
    val line2 = Color(0xFF36495B)
    val text = Color(0xFFEAF1F7)
    val muted = Color(0xFF8EA0B2)
    val muted2 = Color(0xFF6F8295)
    val accent = Color(0xFF2FA566)
    val blue = Color(0xFF3A7BD5)
    val danger = Color(0xFFD35650)
    val warn = Color(0xFFD9A441)
}

private val scheme = darkColorScheme(
    primary = C.blue, onPrimary = Color.White,
    primaryContainer = C.blue, onPrimaryContainer = Color.White,
    secondary = C.accent, onSecondary = Color.White,
    secondaryContainer = C.card2, onSecondaryContainer = C.text,
    tertiary = C.warn,
    background = C.bg, onBackground = C.text,
    surface = C.bg, onSurface = C.text,
    surfaceVariant = C.card2, onSurfaceVariant = C.muted,
    surfaceContainerLowest = C.bg2, surfaceContainerLow = C.card,
    surfaceContainer = C.card, surfaceContainerHigh = C.card2, surfaceContainerHighest = C.card2,
    outline = C.line2, outlineVariant = C.line,
    error = C.danger, onError = Color.White,
    inverseSurface = C.card2, inverseOnSurface = C.text, inversePrimary = C.blue,
)

@Composable
fun SlamTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
