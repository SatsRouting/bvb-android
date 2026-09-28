package com.bvb.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BitcoinOrange = Color(0xFFF7931A)
val DeepNavy = Color(0xFF0D1B2A)
val NavySurface = Color(0xFF1B263B)
val NavySurfaceHigh = Color(0xFF24344D)
val TextOnDark = Color(0xFFE0E1DD)
val SuccessGreen = Color(0xFF2E7D32)
val ErrorRed = Color(0xFFC62828)
val WarningAmber = Color(0xFFF9A825)

// Same buy/sell accent colors as the web app (--order-buy / --order-sell).
val OrderBuy = Color(0xFFC879FF)
val OrderSell = Color(0xFF5DB6FF)

private val DarkColors = darkColorScheme(
    primary = BitcoinOrange,
    onPrimary = Color.Black,
    secondary = Color(0xFF778DA9),
    background = DeepNavy,
    onBackground = TextOnDark,
    surface = NavySurface,
    onSurface = TextOnDark,
    surfaceVariant = NavySurfaceHigh,
    onSurfaceVariant = Color(0xFFB0B8C4),
    error = Color(0xFFEF5350),
)

private val LightColors = lightColorScheme(
    primary = BitcoinOrange,
    onPrimary = Color.White,
    secondary = Color(0xFF415A77),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1B263B),
    surface = Color.White,
    onSurface = Color(0xFF1B263B),
    surfaceVariant = Color(0xFFEDF0F4),
    onSurfaceVariant = Color(0xFF5A6B80),
    error = ErrorRed,
)

@Composable
fun BvbTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
