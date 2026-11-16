package app.mbox.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0B0D12)
val Surface1 = Color(0xFF141821)
val Surface2 = Color(0xFF1C2230)
val Accent = Color(0xFFE50914)
val TextMain = Color(0xFFF2F4F8)
val TextDim = Color(0xFF9AA4B8)

@Composable
fun MBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = Accent,
            background = Bg,
            onBackground = TextMain,
            surface = Surface1,
            onSurface = TextMain,
            surfaceVariant = Surface2,
            onSurfaceVariant = TextDim,
            outline = Color(0xFF2A3140),
        ),
        typography = Typography(),
        content = content,
    )
}
