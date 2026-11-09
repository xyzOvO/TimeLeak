package com.chengliu.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

/**
 * 界面色板。浅色是纯白 + 浅灰蓝卡片，深色是近黑 + 深灰卡片，
 * 主操作按钮在两套里分别取亮蓝与深青蓝，彩色只出现在小图标上。
 */
data class UiColors(
    val bg: Color,
    val card: Color,
    val text: Color,
    val sub: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val active: Color,
    val warn: Color,
)

private val LightUi = UiColors(
    bg = Color(0xFFFFFFFF),
    card = Color(0xFFF3F5F9),
    text = Color(0xFF1B1E23),
    sub = Color(0xFF8B919B),
    line = Color(0xFFE6E9EE),
    accent = Color(0xFF3B6DF2),
    onAccent = Color(0xFFFFFFFF),
    active = Color(0xFF2E9E7B),
    warn = Color(0xFFD9534F),
)

private val DarkUi = UiColors(
    bg = Color(0xFF141519),
    card = Color(0xFF22242B),
    text = Color(0xFFF1F2F5),
    sub = Color(0xFF8A8E98),
    line = Color(0xFF2C2F36),
    accent = Color(0xFF1B7193),
    onAccent = Color(0xFFFFFFFF),
    active = Color(0xFF43B08C),
    warn = Color(0xFFE0665C),
)

val LocalUi = staticCompositionLocalOf { LightUi }

/** mode: system / light / dark */
@Suppress("DEPRECATION")
@Composable
fun ChengliuTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val ui = if (dark) DarkUi else LightUi
    val scheme = if (dark) {
        darkColorScheme(
            primary = ui.accent,
            onPrimary = ui.onAccent,
            primaryContainer = ui.card,
            onPrimaryContainer = ui.text,
            background = ui.bg,
            onBackground = ui.text,
            surface = ui.card,
            onSurface = ui.text,
            surfaceVariant = ui.card,
            onSurfaceVariant = ui.sub,
            outline = ui.line,
            error = ui.warn,
        )
    } else {
        lightColorScheme(
            primary = ui.accent,
            onPrimary = ui.onAccent,
            primaryContainer = ui.card,
            onPrimaryContainer = ui.text,
            background = ui.bg,
            onBackground = ui.text,
            surface = ui.card,
            onSurface = ui.text,
            surfaceVariant = ui.card,
            onSurfaceVariant = ui.sub,
            outline = ui.line,
            error = ui.warn,
        )
    }

    val ctx = LocalContext.current
    SideEffect {
        val window = (ctx as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = ui.bg.toArgb()
        window.navigationBarColor = ui.bg.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalUi provides ui) {
            content()
        }
    }
}
