package com.owlacoustics.roomplanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Revisable accessible palette. Charcoal for treatment visuals per brief; amber accent for selection.
val Charcoal = Color(0xFF2B2D31)
val Amber = Color(0xFFF2C14E)
val Teal = Color(0xFF1F7A72)
val ErrorRed = Color(0xFFB3261E)
val Sufficient = Color(0xFF2E7D32)

private val Light = lightColorScheme(
    primary = Teal, onPrimary = Color.White, secondary = Charcoal, onSecondary = Color.White,
    tertiary = Color(0xFF8A5A00), background = Color(0xFFFAFAF7), surface = Color.White, error = ErrorRed,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF7FD3C9), onPrimary = Color(0xFF00201D), secondary = Color(0xFFDADCE0),
    tertiary = Amber, background = Color(0xFF151618), surface = Color(0xFF1E1F22), error = Color(0xFFF2B8B5),
)

@Composable
fun OwlTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
