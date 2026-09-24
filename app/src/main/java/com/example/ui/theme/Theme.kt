package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

fun getSplinterColorScheme(themeOption: ThemeOption): ColorScheme {
    return when (themeOption) {
        ThemeOption.DARK_SLATE -> darkColorScheme(
            primary = ElectricCyan,
            onPrimary = Color(0xFF031622),
            primaryContainer = Color(0xFF083344),
            onPrimaryContainer = Color(0xFFBAE6FD),
            secondary = NeonBlue,
            onSecondary = Color(0xFF0C1929),
            background = Color(0xFF0B0F19),
            onBackground = Color(0xFFF1F5F9),
            surface = Color(0xFF131A29),
            onSurface = Color(0xFFF8FAFC),
            surfaceVariant = Color(0xFF1B2438),
            onSurfaceVariant = Color(0xFF94A3B8),
            outline = Color(0xFF334155)
        )
        ThemeOption.LIGHT_CHARCOAL -> lightColorScheme(
            primary = Color(0xFF0F172A),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE2E8F0),
            onPrimaryContainer = Color(0xFF0F172A),
            secondary = Color(0xFF2563EB),
            onSecondary = Color.White,
            background = Color(0xFFF8FAFC),
            onBackground = Color(0xFF0F172A),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF0F172A),
            surfaceVariant = Color(0xFFF1F5F9),
            onSurfaceVariant = Color(0xFF475569),
            outline = Color(0xFFCBD5E1)
        )
        ThemeOption.CREAM_BRONZE -> lightColorScheme(
            primary = Color(0xFF78350F),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFDE68A),
            onPrimaryContainer = Color(0xFF451A03),
            secondary = Color(0xFFB45309),
            onSecondary = Color.White,
            background = Color(0xFFFAF5EE),
            onBackground = Color(0xFF292524),
            surface = Color(0xFFF4ECE1),
            onSurface = Color(0xFF1C1917),
            surfaceVariant = Color(0xFFEADCCB),
            onSurfaceVariant = Color(0xFF57534E),
            outline = Color(0xFFD6C7B2)
        )
        ThemeOption.CRIMSON_DARK -> darkColorScheme(
            primary = CrimsonRed,
            onPrimary = Color.White,
            primaryContainer = Color(0xFF450A0A),
            onPrimaryContainer = Color(0xFFFECACA),
            secondary = Color(0xFFF87171),
            onSecondary = Color(0xFF1F0404),
            background = Color(0xFF0E0E11),
            onBackground = Color(0xFFF4F4F5),
            surface = Color(0xFF18181D),
            onSurface = Color(0xFFF4F4F5),
            surfaceVariant = Color(0xFF24242C),
            onSurfaceVariant = Color(0xFFA1A1AA),
            outline = Color(0xFF3F3F46)
        )
        ThemeOption.CYBERPUNK_OLED -> darkColorScheme(
            primary = MatrixGreen,
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF003814),
            onPrimaryContainer = MatrixGreen,
            secondary = CyberCyan,
            onSecondary = Color.Black,
            background = Color(0xFF000000),
            onBackground = Color(0xFFE6FFED),
            surface = Color(0xFF0A0F0D),
            onSurface = Color(0xFFE6FFED),
            surfaceVariant = Color(0xFF121B16),
            onSurfaceVariant = Color(0xFF6EE7B7),
            outline = Color(0xFF005220)
        )
        ThemeOption.GRAY_RED -> darkColorScheme(
            primary = Color(0xFFFF3B30),
            onPrimary = Color.White,
            primaryContainer = Color(0xFF3B1212),
            onPrimaryContainer = Color(0xFFFFB4AB),
            secondary = Color(0xFFE06C75),
            onSecondary = Color.Black,
            background = Color(0xFF1A1C20),
            onBackground = Color(0xFFE6E8EB),
            surface = Color(0xFF24272E),
            onSurface = Color(0xFFE6E8EB),
            surfaceVariant = Color(0xFF2F333C),
            onSurfaceVariant = Color(0xFFABB2BF),
            outline = Color(0xFF4B5263)
        )
    }
}

@Composable
fun SplinterMeshTheme(
    selectedTheme: ThemeOption = ThemeOption.DARK_SLATE,
    content: @Composable () -> Unit
) {
    val colorScheme = getSplinterColorScheme(selectedTheme)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
