package com.candy

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The colours of the current theme, so the picture looks like the chat does. */
internal class AiSnapColors(val bg: Int, val bubble: Int, val text: Int, val accent: Int)

private class FaceSpan(private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(p: TextPaint) {
        p.typeface = face
    }

    override fun updateMeasureState(p: TextPaint) {
        p.typeface = face
    }
}

/** Same light markdown as the chat: **bold** and "- " lists. */
private fun snapText(text: String, bold: Typeface): CharSequence {
    val out = SpannableStringBuilder()
    val lines = text.split("\n")
    for ((li, raw) in lines.withIndex()) {
        val line = if (raw.startsWith("- ") || raw.startsWith("* ")) "• " + raw.substring(2) else raw
        var i = 0
        while (i < line.length) {
            val s = line.indexOf("**", i)
            val e = if (s >= 0) line.indexOf("**", s + 2) else -1
            if (s < 0 || e < 0) {
                out.append(line.substring(i))
                break
            }
            out.append(line.substring(i, s))
            val from = out.length
            out.append(line.substring(s + 2, e))
            if (out.length > from) out.setSpan(FaceSpan(bold), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = e + 2
        }
        if (li < lines.lastIndex) out.append("\n")
    }
    return out
}

/** Draws Candy's reply as a picture: her photo and name on top, the reply in a bubble under them. */
private fun renderAiSnapshot(ctx: Context, text: String, name: String, c: AiSnapColors): Bitmap {
    val w = 1080
    val pad = 64f
    val avatar = 112f
    val gap = 36f
    val bubblePadX = 52f
    val bubblePadY = 44f
    val light = ResourcesCompat.getFont(ctx, R.font.amman_light) ?: Typeface.DEFAULT
    val bold = ResourcesCompat.getFont(ctx, R.font.amman_bold) ?: Typeface.DEFAULT_BOLD

    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c.text
        typeface = light
        textSize = 46f
    }
    val body = snapText(text, bold)
    val textW = (w - 2 * pad - 2 * bubblePadX).toInt()
    val layout = StaticLayout.Builder.obtain(body, 0, body.length, tp, textW)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setLineSpacing(0f, 1.3f)
        .setIncludePad(false)
        .build()
    val rtl = layout.lineCount > 0 && layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT

    val bubbleH = layout.height + 2 * bubblePadY
    val h = (pad + avatar + gap + bubbleH + pad).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(c.bg)

    // photo (a circle) and name; they sit on the side the text starts from
    val ax = if (rtl) w - pad - avatar else pad
    val photo = BitmapFactory.decodeResource(ctx.resources, R.drawable.candy_ai_avatar)
    if (photo != null) {
        val circle = Path().apply { addCircle(ax + avatar / 2, pad + avatar / 2, avatar / 2, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(circle)
        canvas.drawBitmap(photo, null, RectF(ax, pad, ax + avatar, pad + avatar), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
        photo.recycle()
    }
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c.text
        typeface = bold
        textSize = 52f
        textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
    }
    val nameX = if (rtl) ax - 28f else ax + avatar + 28f
    val baseline = pad + avatar / 2 - (namePaint.descent() + namePaint.ascent()) / 2
    canvas.drawText(name, nameX, baseline, namePaint)

    // the reply bubble
    val top = pad + avatar + gap
    val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.bubble }
    canvas.drawRoundRect(RectF(pad, top, w - pad, top + bubbleH), 56f, 56f, bubble)
    canvas.save()
    canvas.translate(pad + bubblePadX, top + bubblePadY)
    layout.draw(canvas)
    canvas.restore()
    return bmp
}

/** Shares Candy's reply as a picture (the share sheet of the phone opens). Returns false when it could not be made. */
internal suspend fun shareAiSnapshot(ctx: Context, text: String, colors: AiSnapColors): Boolean {
    val file = withContext(Dispatchers.IO) {
        try {
            val bmp = renderAiSnapshot(ctx, text.trim(), ctx.getString(R.string.ai_name), colors)
            val dir = File(ctx.cacheDir, "share").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() }
            }
            val f = File(dir, "candy_${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            f
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    } ?: return false
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        ctx.startActivity(chooser)
        true
    } catch (e: Exception) {
        false
    }
}
