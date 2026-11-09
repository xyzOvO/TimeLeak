package com.chengliu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.sin

/** 全部图标为本项目自绘的 24×24 线性图形，不引入任何图标库或第三方素材。 */
enum class Glyph {
    MENU, BACK, HOME, RULES, UPSTREAM, BLOCKED, ALLOWED, SUBSCRIBE, PRIVACY, ABOUT, THEME, PLAY, STOP, SHIELD, LOOK, SKIP,
}

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawGlyph(glyph, tint)
        }
    }
}

private fun DrawScope.drawGlyph(g: Glyph, c: Color) {
    val st = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (g) {
        Glyph.MENU -> {
            drawLine(c, Offset(3f, 6f), Offset(21f, 6f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(3f, 12f), Offset(21f, 12f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(3f, 18f), Offset(21f, 18f), 1.8f, StrokeCap.Round)
        }

        Glyph.BACK -> {
            drawLine(c, Offset(20f, 12f), Offset(5f, 12f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(11f, 6f), Offset(5f, 12f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(11f, 18f), Offset(5f, 12f), 1.8f, StrokeCap.Round)
        }

        Glyph.HOME -> {
            val shield = Path().apply {
                moveTo(12f, 2.5f)
                lineTo(20f, 6f)
                lineTo(20f, 12f)
                quadraticTo(20f, 17.6f, 12f, 21.5f)
                quadraticTo(4f, 17.6f, 4f, 12f)
                lineTo(4f, 6f)
                close()
            }
            drawPath(shield, c, style = st)
            drawLine(c, Offset(8f, 10.5f), Offset(16f, 10.5f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(9f, 14.5f), Offset(15f, 14.5f), 1.8f, StrokeCap.Round)
        }

        Glyph.RULES -> {
            drawLine(c, Offset(3f, 6f), Offset(21f, 6f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(3f, 12f), Offset(16f, 12f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(3f, 18f), Offset(11f, 18f), 1.8f, StrokeCap.Round)
            drawCircle(c, 2.1f, Offset(19f, 12f), style = st)
            drawCircle(c, 2.1f, Offset(17f, 18f), style = st)
        }

        Glyph.UPSTREAM -> {
            drawCircle(c, 9f, Offset(12f, 12f), style = st)
            drawLine(c, Offset(12f, 16.6f), Offset(12f, 8.4f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(8.3f, 12.1f), Offset(12f, 8.4f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(15.7f, 12.1f), Offset(12f, 8.4f), 1.8f, StrokeCap.Round)
        }

        Glyph.BLOCKED -> {
            drawCircle(c, 9f, Offset(12f, 12f), style = st)
            drawLine(c, Offset(5.6f, 5.6f), Offset(18.4f, 18.4f), 1.8f, StrokeCap.Round)
        }

        Glyph.ALLOWED -> {
            drawCircle(c, 9f, Offset(12f, 12f), style = st)
            val tick = Path().apply {
                moveTo(7.6f, 12.2f)
                lineTo(10.7f, 15.3f)
                lineTo(16.4f, 9.2f)
            }
            drawPath(tick, c, style = st)
        }

        Glyph.SUBSCRIBE -> {
            drawRoundRect(
                c, topLeft = Offset(5f, 3f), size = Size(14f, 18f),
                cornerRadius = CornerRadius(2f, 2f), style = st,
            )
            drawLine(c, Offset(8.5f, 9f), Offset(15.5f, 9f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(8.5f, 13f), Offset(15.5f, 13f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(8.5f, 17f), Offset(13f, 17f), 1.8f, StrokeCap.Round)
        }

        Glyph.PRIVACY -> {
            drawRoundRect(
                c, topLeft = Offset(5f, 10.5f), size = Size(14f, 10.5f),
                cornerRadius = CornerRadius(2.2f, 2.2f), style = st,
            )
            drawArc(
                c, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(8f, 3.5f), size = Size(8f, 10f), style = st,
            )
            drawLine(c, Offset(12f, 15f), Offset(12f, 17.6f), 1.8f, StrokeCap.Round)
        }

        Glyph.ABOUT -> {
            drawCircle(c, 9f, Offset(12f, 12f), style = st)
            drawCircle(c, 1.15f, Offset(12f, 7.7f))
            drawLine(c, Offset(12f, 11f), Offset(12f, 16.8f), 1.8f, StrokeCap.Round)
        }

        Glyph.THEME -> {
            drawCircle(c, 4.1f, Offset(12f, 12f), style = st)
            for (i in 0 until 8) {
                val a = Math.toRadians((i * 45).toDouble())
                val cs = cos(a).toFloat()
                val sn = sin(a).toFloat()
                drawLine(
                    c,
                    Offset(12f + 6.8f * cs, 12f + 6.8f * sn),
                    Offset(12f + 9.2f * cs, 12f + 9.2f * sn),
                    1.8f, StrokeCap.Round,
                )
            }
        }

        Glyph.SHIELD -> {            val shield = Path().apply {
                moveTo(12f, 2.6f)
                lineTo(19.6f, 5.8f)
                lineTo(19.6f, 11.6f)
                quadraticTo(19.6f, 17.2f, 12f, 21.4f)
                quadraticTo(4.4f, 17.2f, 4.4f, 11.6f)
                lineTo(4.4f, 5.8f)
                close()
            }
            drawPath(shield, c, style = st)
            val tick = Path().apply {
                moveTo(8.7f, 11.7f)
                lineTo(11.2f, 14.3f)
                lineTo(15.5f, 9.2f)
            }
            drawPath(tick, c, style = st)
        }

        Glyph.LOOK -> {
            drawCircle(c, 6.6f, Offset(10.6f, 10.6f), style = st)
            drawLine(c, Offset(15.4f, 15.4f), Offset(20.4f, 20.4f), 2.4f, StrokeCap.Round)
            drawLine(c, Offset(7.20f, 10.9f), Offset(14.0f, 10.9f), 1.5f, StrokeCap.Round)
            drawLine(c, Offset(7.20f, 14.0f), Offset(11.8f, 14.0f), 1.5f, StrokeCap.Round)
        }

        Glyph.PLAY -> {
            val triangle = Path().apply {
                moveTo(9f, 5.6f)
                lineTo(19f, 12f)
                lineTo(9f, 18.4f)
                close()
            }
            drawPath(triangle, c)
        }

        Glyph.STOP -> {
            drawRoundRect(
                c, topLeft = Offset(8f, 8f), size = Size(8f, 8f),
                cornerRadius = CornerRadius(1.6f, 1.6f),
            )
        }

        Glyph.SKIP -> {
            // 快进 / 跳过：双右箭头 + 竖线
            drawLine(c, Offset(7f, 5f), Offset(13f, 12f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(7f, 19f), Offset(13f, 12f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(14.5f, 5f), Offset(14.5f, 19f), 1.8f, StrokeCap.Round)
            drawLine(c, Offset(17f, 5f), Offset(17f, 19f), 1.8f, StrokeCap.Round)
        }
    }
}
