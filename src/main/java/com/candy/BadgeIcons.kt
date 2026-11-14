package com.candy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The mark next to a name. [icon] is "check" (the official verification) or the name of one of the badges that come with
 * the Kanade plans; [color] is a colour the owner chose (Elite members and officially verified accounts can), or null.
 */
class Badge(val icon: String, val tier: String?, val color: Int?)

fun JSONObject?.toBadge(): Badge? {
    if (this == null) return null
    val icon = optString("icon")
    if (icon.isBlank()) return null
    val tier = if (isNull("tier")) null else optString("tier").ifBlank { null }
    val color = if (isNull("color")) {
        null
    } else {
        try {
            android.graphics.Color.parseColor(optString("color"))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    return Badge(icon, tier, color)
}

/** The names of all the badges, in the order the picker shows them. */
val BadgeIconNames = listOf(
    "check", "star", "splat", "trident", "asclepius", "fleur", "crown",
    "gem", "hat", "horn", "medal", "sport", "trophy", "gold",
)

/** The badge of an account: the one it chose, or the blue check when it is just verified. Nothing when it has neither. */
@Composable
fun UserBadge(verified: Boolean, badge: Badge?, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    val icon = badge?.icon ?: if (verified) "check" else null
    if (icon != null) BadgeIcon(icon, size, badge?.color?.let { Color(it) }, modifier)
}

/** One badge, drawn. It is the same on every phone because it is not an emoji, it is a picture made of shapes. */
@Composable
fun BadgeIcon(icon: String, size: Dp = 16.dp, color: Color? = null, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawBadgeIcon(icon, color) }
}

private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float, points: Int, start: Double): Path {
    val path = Path()
    val n = points * 2
    for (i in 0 until n) {
        val r = if (i % 2 == 0) outer else inner
        val a = start + PI * i / points
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun DrawScope.drawBadgeIcon(icon: String, custom: Color?) {
    val s = size.minDimension
    fun p(v: Float) = v * s
    fun grad(top: Color, bottom: Color): Brush = Brush.verticalGradient(listOf(top, bottom), startY = 0f, endY = s)
    // the main colour of a badge: its own, or the owner's colour made lighter on top and darker below
    fun body(top: Color, bottom: Color): Brush =
        if (custom != null) grad(lerp(custom, Color.White, 0.38f), lerp(custom, Color.Black, 0.22f)) else grad(top, bottom)
    fun round(w: Float) = Stroke(width = p(w), cap = StrokeCap.Round, join = StrokeJoin.Round)

    when (icon) {
        // the official check: a soft eight-lobed badge with a white tick
        "check" -> {
            val base = custom ?: Color(0xFF0A84FF)
            val path = Path()
            val steps = 160
            for (i in 0..steps) {
                val a = 2.0 * PI * i / steps
                val r = p(0.40f) * (1f + 0.085f * cos(8.0 * a).toFloat())
                val x = p(0.5f) + r * cos(a).toFloat()
                val y = p(0.5f) + r * sin(a).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, base)
            val tick = Path().apply {
                moveTo(p(0.29f), p(0.52f))
                lineTo(p(0.44f), p(0.67f))
                lineTo(p(0.71f), p(0.37f))
            }
            drawPath(tick, Color.White, style = round(0.105f))
        }

        // a rounded gold star with two sparkles
        "star" -> {
            val b = body(Color(0xFFFFE680), Color(0xFFFFA800))
            val star = starPath(p(0.46f), p(0.54f), p(0.36f), p(0.165f), 5, -PI / 2)
            drawPath(star, b)
            drawPath(star, b, style = Stroke(width = p(0.07f), join = StrokeJoin.Round))
            val glint = Color(0xFFFFF3B0)
            drawPath(starPath(p(0.82f), p(0.20f), p(0.13f), p(0.04f), 4, -PI / 2), glint)
            drawPath(starPath(p(0.17f), p(0.22f), p(0.08f), p(0.025f), 4, -PI / 2), glint)
        }

        // a splash of colour with drops flying off it
        "splat" -> {
            val b = body(Color(0xFFFF7AC8), Color(0xFF8B5CF6))
            val blob = Path()
            val steps = 140
            for (i in 0..steps) {
                val a = 2.0 * PI * i / steps
                val r = p(0.27f) * (1f + 0.22f * cos(3.0 * a).toFloat() + 0.10f * cos(5.0 * a + 1.0).toFloat())
                val x = p(0.5f) + r * cos(a).toFloat()
                val y = p(0.5f) + r * sin(a).toFloat()
                if (i == 0) blob.moveTo(x, y) else blob.lineTo(x, y)
            }
            blob.close()
            drawPath(blob, b)
            drawCircle(b, p(0.075f), Offset(p(0.17f), p(0.27f)))
            drawCircle(b, p(0.055f), Offset(p(0.84f), p(0.24f)))
            drawCircle(b, p(0.085f), Offset(p(0.82f), p(0.83f)))
            drawCircle(b, p(0.05f), Offset(p(0.15f), p(0.80f)))
        }

        // three prongs on a staff
        "trident" -> {
            val b = body(Color(0xFF5EEAD4), Color(0xFF0EA5E9))
            drawLine(b, Offset(p(0.5f), p(0.30f)), Offset(p(0.5f), p(0.93f)), strokeWidth = p(0.07f), cap = StrokeCap.Round)
            val u = Path().apply {
                moveTo(p(0.22f), p(0.22f))
                lineTo(p(0.22f), p(0.38f))
                cubicTo(p(0.22f), p(0.52f), p(0.36f), p(0.58f), p(0.5f), p(0.58f))
                cubicTo(p(0.64f), p(0.58f), p(0.78f), p(0.52f), p(0.78f), p(0.38f))
                lineTo(p(0.78f), p(0.22f))
            }
            drawPath(u, b, style = round(0.07f))
            fun tip(x: Float, top: Float, base: Float, half: Float) {
                val t = Path().apply {
                    moveTo(p(x), p(top))
                    lineTo(p(x - half), p(base))
                    lineTo(p(x + half), p(base))
                    close()
                }
                drawPath(t, b)
                drawPath(t, b, style = Stroke(width = p(0.03f), join = StrokeJoin.Round))
            }
            tip(0.5f, 0.06f, 0.27f, 0.10f)
            tip(0.22f, 0.06f, 0.25f, 0.08f)
            tip(0.78f, 0.06f, 0.25f, 0.08f)
        }

        // a rod with a snake winding up it
        "asclepius" -> {
            val b = body(Color(0xFF6EE7B7), Color(0xFF059669))
            drawLine(b, Offset(p(0.5f), p(0.14f)), Offset(p(0.5f), p(0.92f)), strokeWidth = p(0.065f), cap = StrokeCap.Round)
            drawCircle(b, p(0.075f), Offset(p(0.5f), p(0.11f)))
            val snake = Path().apply {
                moveTo(p(0.5f), p(0.80f))
                cubicTo(p(0.12f), p(0.72f), p(0.14f), p(0.54f), p(0.5f), p(0.48f))
                cubicTo(p(0.86f), p(0.42f), p(0.88f), p(0.26f), p(0.5f), p(0.22f))
            }
            drawPath(snake, b, style = round(0.07f))
            drawCircle(b, p(0.06f), Offset(p(0.5f), p(0.21f)))
        }

        // a lily: one tall petal, two curling ones, a band
        "fleur" -> {
            val b = body(Color(0xFFC4B5FD), Color(0xFF6D28D9))
            val centre = Path().apply {
                moveTo(p(0.5f), p(0.06f))
                cubicTo(p(0.72f), p(0.24f), p(0.66f), p(0.46f), p(0.5f), p(0.60f))
                cubicTo(p(0.34f), p(0.46f), p(0.28f), p(0.24f), p(0.5f), p(0.06f))
                close()
            }
            drawPath(centre, b)
            val side = Path().apply {
                moveTo(p(0.40f), p(0.44f))
                cubicTo(p(0.12f), p(0.24f), p(0.02f), p(0.56f), p(0.22f), p(0.66f))
                cubicTo(p(0.32f), p(0.71f), p(0.44f), p(0.66f), p(0.46f), p(0.58f))
                close()
            }
            drawPath(side, b)
            scale(-1f, 1f, Offset(p(0.5f), p(0.5f))) { drawPath(side, b) }
            drawRoundRect(b, Offset(p(0.27f), p(0.60f)), Size(p(0.46f), p(0.09f)), CornerRadius(p(0.045f)))
            val tail = Path().apply {
                moveTo(p(0.42f), p(0.70f))
                lineTo(p(0.58f), p(0.70f))
                lineTo(p(0.5f), p(0.94f))
                close()
            }
            drawPath(tail, b)
        }

        // a crown with three points and jewels
        "crown" -> {
            val b = body(Color(0xFFFFE066), Color(0xFFFF9F0A))
            val crown = Path().apply {
                moveTo(p(0.12f), p(0.74f))
                lineTo(p(0.07f), p(0.32f))
                lineTo(p(0.30f), p(0.52f))
                lineTo(p(0.5f), p(0.20f))
                lineTo(p(0.70f), p(0.52f))
                lineTo(p(0.93f), p(0.32f))
                lineTo(p(0.88f), p(0.74f))
                close()
            }
            drawPath(crown, b)
            drawPath(crown, b, style = Stroke(width = p(0.05f), join = StrokeJoin.Round))
            drawRoundRect(b, Offset(p(0.12f), p(0.76f)), Size(p(0.76f), p(0.12f)), CornerRadius(p(0.04f)))
            drawCircle(b, p(0.055f), Offset(p(0.07f), p(0.30f)))
            drawCircle(b, p(0.06f), Offset(p(0.5f), p(0.17f)))
            drawCircle(b, p(0.055f), Offset(p(0.93f), p(0.30f)))
            drawCircle(Color(0xFFFF4D6D), p(0.03f), Offset(p(0.5f), p(0.82f)))
        }

        // a cut gem with its facets
        "gem" -> {
            val b = body(Color(0xFF7DD3FC), Color(0xFF2563EB))
            val gem = Path().apply {
                moveTo(p(0.27f), p(0.20f))
                lineTo(p(0.73f), p(0.20f))
                lineTo(p(0.93f), p(0.42f))
                lineTo(p(0.5f), p(0.90f))
                lineTo(p(0.07f), p(0.42f))
                close()
            }
            drawPath(gem, b)
            drawPath(gem, b, style = Stroke(width = p(0.04f), join = StrokeJoin.Round))
            val facet = Color.White.copy(alpha = 0.55f)
            val line = Stroke(width = p(0.025f), cap = StrokeCap.Round)
            fun cut(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(facet, Offset(p(x1), p(y1)), Offset(p(x2), p(y2)), strokeWidth = line.width, cap = StrokeCap.Round)
            cut(0.07f, 0.42f, 0.93f, 0.42f)
            cut(0.27f, 0.20f, 0.38f, 0.42f)
            cut(0.73f, 0.20f, 0.62f, 0.42f)
            cut(0.38f, 0.42f, 0.5f, 0.90f)
            cut(0.62f, 0.42f, 0.5f, 0.90f)
            val glint = Path().apply {
                moveTo(p(0.27f), p(0.20f))
                lineTo(p(0.38f), p(0.42f))
                lineTo(p(0.07f), p(0.42f))
                close()
            }
            drawPath(glint, Color.White.copy(alpha = 0.18f))
        }

        // a top hat with a band
        "hat" -> {
            val b = body(Color(0xFF64748B), Color(0xFF0F172A))
            drawRoundRect(b, Offset(p(0.27f), p(0.12f)), Size(p(0.46f), p(0.56f)), CornerRadius(p(0.07f)))
            drawRoundRect(b, Offset(p(0.10f), p(0.64f)), Size(p(0.80f), p(0.14f)), CornerRadius(p(0.07f)))
            drawRect(Color(0xFFFB7185), Offset(p(0.27f), p(0.50f)), Size(p(0.46f), p(0.10f)))
            drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(p(0.33f), p(0.16f)), Size(p(0.06f), p(0.30f)), CornerRadius(p(0.03f)))
        }

        // a coiled post horn
        "horn" -> {
            val b = body(Color(0xFFFFE066), Color(0xFFF59E0B))
            drawCircle(b, p(0.24f), Offset(p(0.58f), p(0.56f)), style = Stroke(width = p(0.10f)))
            drawLine(b, Offset(p(0.74f), p(0.40f)), Offset(p(0.90f), p(0.15f)), strokeWidth = p(0.09f), cap = StrokeCap.Round)
            drawCircle(b, p(0.065f), Offset(p(0.91f), p(0.13f)))
            val bell = Path().apply {
                moveTo(p(0.38f), p(0.47f))
                lineTo(p(0.07f), p(0.28f))
                cubicTo(p(0.0f), p(0.42f), p(0.0f), p(0.66f), p(0.07f), p(0.80f))
                lineTo(p(0.38f), p(0.65f))
                close()
            }
            drawPath(bell, b)
        }

        // a medal of honour: a V ribbon and a gold disc with a star
        "medal" -> {
            val ribbon = grad(Color(0xFFF87171), Color(0xFFB91C1C))
            val strip = Path().apply {
                moveTo(p(0.26f), p(0.04f))
                lineTo(p(0.44f), p(0.04f))
                lineTo(p(0.62f), p(0.46f))
                lineTo(p(0.44f), p(0.46f))
                close()
            }
            drawPath(strip, ribbon)
            scale(-1f, 1f, Offset(p(0.5f), p(0.5f))) { drawPath(strip, ribbon) }
            val b = body(Color(0xFFFFE066), Color(0xFFFFA000))
            drawCircle(b, p(0.27f), Offset(p(0.5f), p(0.68f)))
            drawCircle(Color.White.copy(alpha = 0.5f), p(0.20f), Offset(p(0.5f), p(0.68f)), style = Stroke(width = p(0.03f)))
            drawPath(starPath(p(0.5f), p(0.68f), p(0.12f), p(0.05f), 5, -PI / 2), Color.White.copy(alpha = 0.92f))
        }

        // a sports medal: a straight striped ribbon and a bronze disc
        "sport" -> {
            drawRect(Color(0xFF3B82F6), Offset(p(0.36f), p(0.04f)), Size(p(0.28f), p(0.42f)))
            drawRect(Color.White, Offset(p(0.46f), p(0.04f)), Size(p(0.08f), p(0.42f)))
            val b = body(Color(0xFFFDBA74), Color(0xFFEA580C))
            drawCircle(b, p(0.27f), Offset(p(0.5f), p(0.69f)))
            drawCircle(Color.White.copy(alpha = 0.5f), p(0.20f), Offset(p(0.5f), p(0.69f)), style = Stroke(width = p(0.03f)))
            drawCircle(Color.White.copy(alpha = 0.85f), p(0.07f), Offset(p(0.5f), p(0.69f)))
        }

        // a cup on a stem
        "trophy" -> {
            val b = body(Color(0xFFFFE066), Color(0xFFF59E0B))
            val handle = Path().apply {
                moveTo(p(0.27f), p(0.17f))
                cubicTo(p(0.04f), p(0.14f), p(0.06f), p(0.42f), p(0.31f), p(0.44f))
            }
            drawPath(handle, b, style = round(0.06f))
            scale(-1f, 1f, Offset(p(0.5f), p(0.5f))) { drawPath(handle, b, style = round(0.06f)) }
            val cup = Path().apply {
                moveTo(p(0.26f), p(0.12f))
                lineTo(p(0.74f), p(0.12f))
                lineTo(p(0.70f), p(0.40f))
                cubicTo(p(0.68f), p(0.56f), p(0.58f), p(0.62f), p(0.5f), p(0.62f))
                cubicTo(p(0.42f), p(0.62f), p(0.32f), p(0.56f), p(0.30f), p(0.40f))
                close()
            }
            drawPath(cup, b)
            drawRect(b, Offset(p(0.455f), p(0.62f)), Size(p(0.09f), p(0.16f)))
            drawRoundRect(b, Offset(p(0.30f), p(0.76f)), Size(p(0.40f), p(0.12f)), CornerRadius(p(0.04f)))
            drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(p(0.33f), p(0.16f)), Size(p(0.05f), p(0.22f)), CornerRadius(p(0.025f)))
        }

        // first place: a V ribbon and a gold disc with a 1
        "gold" -> {
            val ribbon = grad(Color(0xFF60A5FA), Color(0xFF1D4ED8))
            val strip = Path().apply {
                moveTo(p(0.26f), p(0.04f))
                lineTo(p(0.44f), p(0.04f))
                lineTo(p(0.62f), p(0.46f))
                lineTo(p(0.44f), p(0.46f))
                close()
            }
            drawPath(strip, ribbon)
            scale(-1f, 1f, Offset(p(0.5f), p(0.5f))) { drawPath(strip, ribbon) }
            val b = body(Color(0xFFFFE680), Color(0xFFFFB300))
            drawCircle(b, p(0.27f), Offset(p(0.5f), p(0.68f)))
            drawCircle(Color.White.copy(alpha = 0.5f), p(0.20f), Offset(p(0.5f), p(0.68f)), style = Stroke(width = p(0.03f)))
            val one = Path().apply {
                moveTo(p(0.44f), p(0.60f))
                lineTo(p(0.52f), p(0.53f))
                lineTo(p(0.52f), p(0.81f))
            }
            drawPath(one, Color(0xFF9A5B00), style = round(0.07f))
            drawLine(Color(0xFF9A5B00), Offset(p(0.43f), p(0.82f)), Offset(p(0.61f), p(0.82f)), strokeWidth = p(0.06f), cap = StrokeCap.Round)
        }
    }
}
