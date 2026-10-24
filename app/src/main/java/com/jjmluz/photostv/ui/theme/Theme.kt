package com.jjmluz.photostv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.darkColorScheme

// Material 3 dark tonal palette seeded from Google blue. Google TV has no wallpaper-based
// dynamic color, so the scheme is fixed.
private val Colors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFBFC6DC),
    onSecondary = Color(0xFF293041),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFDDBCE0),
    onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5C),
    onTertiaryContainer = Color(0xFFFAD8FD),
    background = Color(0xFF131314),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF131314),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF282A2C),
    onSurfaceVariant = Color(0xFFC4C7C5),
    inverseSurface = Color(0xFFE3E3E3),
    inverseOnSurface = Color(0xFF303030),
    border = Color(0xFF8E918F),
    borderVariant = Color(0xFF444746),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

// Material You's rounder corners: tiles and cards use medium/large, pills use full rounding.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PhotosTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, shapes = AppShapes, content = content)
}
