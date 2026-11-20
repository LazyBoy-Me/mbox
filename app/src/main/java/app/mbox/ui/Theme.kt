package app.mbox.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette taken from the stock app's night resources (values-night/colors.xml):
// module_01 #28292e, base_color #101114, brand #07b84e, brand_new_50 #2ff58b,
// color_score #f9dea2, text_01 white, text_02 white_80, text_03 gray_60.
val Bg = Color(0xFF101114)
val Surface1 = Color(0xFF1B1D22)
val Surface2 = Color(0xFF28292E)
val Surface3 = Color(0xFF33353B)
val Accent = Color(0xFF07B84E)
val AccentSoft = Color(0xFF2FF58B)
val TextMain = Color(0xFFFFFFFF)
val TextDim = Color(0xCCFFFFFF)
val TextMute = Color(0x99FFFFFF)
val Score = Color(0xFFF9DEA2)
val Divider = Color(0x1FFFFFFF)

@Composable
fun MBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            secondary = AccentSoft,
            background = Bg,
            onBackground = TextMain,
            surface = Surface1,
            onSurface = TextMain,
            surfaceVariant = Surface2,
            onSurfaceVariant = TextDim,
            outline = Divider,
        ),
        typography = Typography(),
        content = content,
    )
}
