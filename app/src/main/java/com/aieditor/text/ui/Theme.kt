package com.aieditor.text.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 与参考图中的“发送”按钮一致的品牌绿。 */
val BrandGreen = Color(0xFF3CA55C)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F8F55),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F0E1),
    onPrimaryContainer = Color(0xFF0A3A1C),
    secondary = Color(0xFF51645A),
    onSecondary = Color.White,
    background = Color(0xFFF7F8F6),
    onBackground = Color(0xFF1A1C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFE7E8E5),
    onSurfaceVariant = Color(0xFF444743),
    // 补全 M3 的容器色阶：下拉菜单、对话框等默认取这些值，
    // 不指定时会回落到基线主题的紫灰色，和整体配色冲突。
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F6F2),
    surfaceContainer = Color(0xFFEFF1ED),
    surfaceContainerHigh = Color(0xFFE9EBE7),
    surfaceContainerHighest = Color(0xFFE3E5E1),
    outline = Color(0xFF9AA09A),
    outlineVariant = Color(0xFFD5D8D2),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7ED9A0),
    onPrimary = Color(0xFF00391B),
    primaryContainer = Color(0xFF12522F),
    onPrimaryContainer = Color(0xFFD3F3DE),
    secondary = Color(0xFFB8CCBF),
    onSecondary = Color(0xFF23342A),
    background = Color(0xFF121412),
    onBackground = Color(0xFFE2E4E0),
    surface = Color(0xFF1A1D1A),
    onSurface = Color(0xFFE2E4E0),
    surfaceVariant = Color(0xFF2C302C),
    onSurfaceVariant = Color(0xFFC4C8C2),
    surfaceContainerLowest = Color(0xFF0D0F0D),
    surfaceContainerLow = Color(0xFF171917),
    surfaceContainer = Color(0xFF1B1E1B),
    surfaceContainerHigh = Color(0xFF252825),
    surfaceContainerHighest = Color(0xFF30332F),
    outline = Color(0xFF8D938C),
    outlineVariant = Color(0xFF414640),
    error = Color(0xFFF2B8B5),
)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
