package com.sih.faceattendance.core

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Clean High-Trust Institutional Light Mode Palette
val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightCard = Color(0xFFFFFFFF)
val LightCardBorder = Color(0xFFE2E8F0)
val LightCardBorderStrong = Color(0xFFCBD5E1)
val LightSubtle = Color(0xFFF1F5F9)

// Semantic Accents
val PrimaryBlue = Color(0xFF2563EB)
val PrimaryBlueContainer = Color(0xFFEFF6FF)
val EmeraldVerified = Color(0xFF059669)
val EmeraldContainer = Color(0xFFECFDF5)
val AmberOffline = Color(0xFFD97706)
val AmberContainer = Color(0xFFFEF3C7)
val CrimsonAlert = Color(0xFFDC2626)
val CrimsonContainer = Color(0xFFFEF2F2)
val CyanHUD = Color(0xFF0284C7)
val SkyContainer = Color(0xFFF0F9FF)

// High-contrast clean typography
val TextPrimary = Color(0xFF0F172A)
val TextSecondary = Color(0xFF475569)
val TextMuted = Color(0xFF94A3B8)

// Compatibility aliases for clean light-mode transition
val DarkSlateBackground = LightBackground
val DarkSlateSurface = LightSurface
val DarkSlateCard = LightCard
val DarkSlateBorder = LightCardBorder

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = PrimaryBlueContainer,
    onPrimaryContainer = PrimaryBlue,
    secondary = EmeraldVerified,
    onSecondary = Color.White,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = EmeraldVerified,
    background = LightBackground,
    onBackground = TextPrimary,
    surface = LightSurface,
    onSurface = TextPrimary,
    surfaceVariant = LightSubtle,
    onSurfaceVariant = TextSecondary,
    outline = LightCardBorder,
    error = CrimsonAlert,
    onError = Color.White,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonAlert
)

@Composable
fun OfflineFaceAttendanceTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
