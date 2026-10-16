package com.h4era.screenrecorder

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF070B14)
val CardBg = Color(0xFF111A2E)
val Cyan = Color(0xFF00E5FF)
val Blue = Color(0xFF2979FF)
val Muted = Color(0xFF8A94A6)

object Pal {
    var dark by mutableStateOf(true)
    val bg: Color get() = if (dark) Navy else Color(0xFFF2F5FA)
    val bg2: Color get() = if (dark) Color(0xFF0C1630) else Color(0xFFE6ECF7)
    val card: Color get() = if (dark) CardBg else Color.White
    val card2: Color get() = if (dark) Color(0xFF1B2640) else Color(0xFFDCE4F2)
    val note: Color get() = if (dark) Color(0xFF0F2A3A) else Color(0xFFD9EEF7)
    val text: Color get() = if (dark) Color.White else Color(0xFF0B1220)
    val muted: Color get() = if (dark) Muted else Color(0xFF55627A)
    val accent: Color get() = if (dark) Cyan else Color(0xFF0277BD)
    val onAccent: Color get() = if (dark) Color.Black else Color.White
}

@Composable
fun H4Theme(content: @Composable () -> Unit) {
    val scheme = if (Pal.dark) {
        darkColorScheme(
            primary = Cyan,
            onPrimary = Color.Black,
            secondary = Blue,
            background = Navy,
            onBackground = Color.White,
            surface = CardBg,
            onSurface = Color.White
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0277BD),
            onPrimary = Color.White,
            secondary = Blue,
            background = Color(0xFFF2F5FA),
            onBackground = Color(0xFF0B1220),
            surface = Color.White,
            onSurface = Color(0xFF0B1220)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
