package online.visabud.app.visabud_multiplatform.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import visabud_multiplatform.composeapp.generated.resources.*

@Composable
fun visabudTypography(): Typography {
    val latoFontFamily = FontFamily(
        Font(Res.font.Lato_Thin, FontWeight.Thin),
        Font(Res.font.Lato_ThinItalic, FontWeight.Thin, FontStyle.Italic),
        Font(Res.font.Lato_Light, FontWeight.Light),
        Font(Res.font.Lato_LightItalic, FontWeight.Light, FontStyle.Italic),
        Font(Res.font.Lato_Regular, FontWeight.Normal),
        Font(Res.font.Lato_Italic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.Lato_Bold, FontWeight.Bold),
        Font(Res.font.Lato_BoldItalic, FontWeight.Bold, FontStyle.Italic),
        Font(Res.font.Lato_Black, FontWeight.Black),
        Font(Res.font.Lato_BlackItalic, FontWeight.Black, FontStyle.Italic)
    )

    val zillaSlabFontFamily = FontFamily(
        Font(Res.font.ZillaSlab_Light, FontWeight.Light),
        Font(Res.font.ZillaSlab_LightItalic, FontWeight.Light, FontStyle.Italic),
        Font(Res.font.ZillaSlab_Regular, FontWeight.Normal),
        Font(Res.font.ZillaSlab_Italic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.ZillaSlab_Medium, FontWeight.Medium),
        Font(Res.font.ZillaSlab_MediumItalic, FontWeight.Medium, FontStyle.Italic),
        Font(Res.font.ZillaSlab_SemiBold, FontWeight.SemiBold),
        Font(Res.font.ZillaSlab_SemiBoldItalic, FontWeight.SemiBold, FontStyle.Italic),
        Font(Res.font.ZillaSlab_Bold, FontWeight.Bold),
        Font(Res.font.ZillaSlab_BoldItalic, FontWeight.Bold, FontStyle.Italic)
    )

    val baseline = Typography()

    return Typography(
        displayLarge = baseline.displayLarge.copy(fontFamily = zillaSlabFontFamily),
        displayMedium = baseline.displayMedium.copy(fontFamily = zillaSlabFontFamily),
        displaySmall = baseline.displaySmall.copy(fontFamily = zillaSlabFontFamily),
        headlineLarge = baseline.headlineLarge.copy(fontFamily = zillaSlabFontFamily),
        headlineMedium = baseline.headlineMedium.copy(fontFamily = zillaSlabFontFamily),
        headlineSmall = baseline.headlineSmall.copy(fontFamily = zillaSlabFontFamily),
        titleLarge = baseline.titleLarge.copy(fontFamily = latoFontFamily),
        titleMedium = baseline.titleMedium.copy(fontFamily = latoFontFamily),
        titleSmall = baseline.titleSmall.copy(fontFamily = latoFontFamily),
        bodyLarge = baseline.bodyLarge.copy(fontFamily = latoFontFamily),
        bodyMedium = baseline.bodyMedium.copy(fontFamily = latoFontFamily),
        bodySmall = baseline.bodySmall.copy(fontFamily = latoFontFamily),
        labelLarge = baseline.labelLarge.copy(fontFamily = latoFontFamily),
        labelMedium = baseline.labelMedium.copy(fontFamily = latoFontFamily),
        labelSmall = baseline.labelSmall.copy(fontFamily = latoFontFamily),
    )
}
