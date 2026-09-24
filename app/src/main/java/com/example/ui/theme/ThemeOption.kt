package com.example.ui.theme

enum class ThemeOption(
    val title: String,
    val subtitle: String,
    val primaryHex: Long,
    val backgroundHex: Long
) {
    DARK_SLATE(
        title = "Dark Slate",
        subtitle = "Dark Slate / Neon Blue",
        primaryHex = 0xFF00E5FF,
        backgroundHex = 0xFF0B0F19
    ),
    LIGHT_CHARCOAL(
        title = "Light Clean",
        subtitle = "Clean White / Charcoal",
        primaryHex = 0xFF0F172A,
        backgroundHex = 0xFFF8FAFC
    ),
    CREAM_BRONZE(
        title = "Cream Warm",
        subtitle = "Warm Sand / Dark Bronze",
        primaryHex = 0xFF92400E,
        backgroundHex = 0xFFFAF5EE
    ),
    CRIMSON_DARK(
        title = "Crimson Dark",
        subtitle = "Deep Charcoal / Crimson Red",
        primaryHex = 0xFFEF4444,
        backgroundHex = 0xFF121214
    ),
    CYBERPUNK_OLED(
        title = "Cyberpunk OLED",
        subtitle = "Pure Black / Matrix Green & Cyan",
        primaryHex = 0xFF00FF66,
        backgroundHex = 0xFF000000
    ),
    GRAY_RED(
        title = "Gray & Red",
        subtitle = "Industrial Gray / Bold Red",
        primaryHex = 0xFFFF3B30,
        backgroundHex = 0xFF1C1E24
    )
}
