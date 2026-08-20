package com.example.zezes

import android.graphics.Color

data class Theme(
    val name: String,
    val backgroundColor: Int,
    val cardColor: Int,
    val accent1: Int,
    val accent2: Int,
    val accent3: Int,
    val textColor: Int,
    val secondaryTextColor: Int,
    val terminalTextColor: Int,
    val isLight: Boolean
)

object ThemeManager {
    val themes: List<Theme> = listOf(
        Theme(
            name = "Cyberpunk Neon",
            backgroundColor = Color.parseColor("#0B0F19"),
            cardColor = Color.parseColor("#161B22"),
            accent1 = Color.parseColor("#00F0FF"),
            accent2 = Color.parseColor("#FF007F"),
            accent3 = Color.parseColor("#BC13FE"),
            textColor = -1,
            secondaryTextColor = Color.parseColor("#A0A0A0"),
            terminalTextColor = Color.parseColor("#39FF14"),
            isLight = false
        ),
        Theme(
            name = "AMOLED Pitch Black",
            backgroundColor = -16777216,
            cardColor = Color.parseColor("#121212"),
            accent1 = Color.parseColor("#FF1744"),
            accent2 = Color.parseColor("#FF1744"),
            accent3 = Color.parseColor("#FF1744"),
            textColor = -1,
            secondaryTextColor = Color.parseColor("#888888"),
            terminalTextColor = Color.parseColor("#FF1744"),
            isLight = false
        ),
        Theme(
            name = "Matrix Green",
            backgroundColor = Color.parseColor("#050E05"),
            cardColor = Color.parseColor("#0A1A0A"),
            accent1 = Color.parseColor("#00FF66"),
            accent2 = Color.parseColor("#00CC52"),
            accent3 = Color.parseColor("#00993D"),
            textColor = Color.parseColor("#00FF66"),
            secondaryTextColor = Color.parseColor("#008833"),
            terminalTextColor = Color.parseColor("#00FF66"),
            isLight = false
        ),
        Theme(
            name = "Deep Space Purple",
            backgroundColor = Color.parseColor("#0D0B18"),
            cardColor = Color.parseColor("#1A162D"),
            accent1 = Color.parseColor("#A855F7"),
            accent2 = Color.parseColor("#7C3AED"),
            accent3 = Color.parseColor("#6366F1"),
            textColor = -1,
            secondaryTextColor = Color.parseColor("#94A3B8"),
            terminalTextColor = Color.parseColor("#A855F7"),
            isLight = false
        ),
        Theme(
            name = "Clean Minimal Light",
            backgroundColor = Color.parseColor("#F8FAFC"),
            cardColor = -1,
            accent1 = Color.parseColor("#0F172A"),
            accent2 = Color.parseColor("#334155"),
            accent3 = Color.parseColor("#64748B"),
            textColor = Color.parseColor("#0F172A"),
            secondaryTextColor = Color.parseColor("#64748B"),
            terminalTextColor = Color.parseColor("#0F172A"),
            isLight = true
        ),
        Theme(
            name = "Solarized Light",
            backgroundColor = Color.parseColor("#FDF6E3"),
            cardColor = Color.parseColor("#EEE8D5"),
            accent1 = Color.parseColor("#268BD2"),
            accent2 = Color.parseColor("#2AA198"),
            accent3 = Color.parseColor("#D33682"),
            textColor = Color.parseColor("#586E75"),
            secondaryTextColor = Color.parseColor("#839496"),
            terminalTextColor = Color.parseColor("#268BD2"),
            isLight = true
        )
    )
}
