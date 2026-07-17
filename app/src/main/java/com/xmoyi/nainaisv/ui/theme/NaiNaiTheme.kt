package com.xmoyi.nainaisv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFFFFB59B),
    onPrimary = Color(0xFF5B1909),
    secondary = Color(0xFFFFDCCF),
    background = Color.Black,
    surface = Color(0xFF181210),
)

@Composable
fun NaiNaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
