package com.codex.quota.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Native fonts retain Chinese coverage and follow the user's font scale.
private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default, fontWeight = weight, fontSize = size.sp,
    lineHeight = height.sp, letterSpacing = 0.sp
)

val Typography = Typography(
    displayLarge = type(48, 56, FontWeight.SemiBold),
    displayMedium = type(40, 48, FontWeight.SemiBold),
    displaySmall = type(32, 40, FontWeight.SemiBold),
    headlineLarge = type(28, 36, FontWeight.SemiBold),
    headlineMedium = type(24, 32, FontWeight.SemiBold),
    headlineSmall = type(22, 28, FontWeight.SemiBold),
    titleLarge = type(20, 28, FontWeight.SemiBold),
    titleMedium = type(16, 24, FontWeight.Medium),
    titleSmall = type(14, 20, FontWeight.Medium),
    bodyLarge = type(16, 24),
    bodyMedium = type(14, 20),
    bodySmall = type(12, 18),
    labelLarge = type(14, 20, FontWeight.Medium),
    labelMedium = type(12, 18, FontWeight.Medium),
    labelSmall = type(12, 16, FontWeight.Medium)
)
