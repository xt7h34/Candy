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
internal class AiSnapColors(val bg: Int, val bubble: Int, val text: Int, val accent: Int, val mineText: Int)

/** One message of the conversation that is being shared. */
internal class AiSnapMsg(val text: String, val mine: Boolean)

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

private class AiMeasured(val mine: Boolean, val layout: StaticLayout, val bubbleW: Float, val bubbleH: Float)

/** Draws the whole conversation: her photo and name on top, then the bubbles. A long one is cut into several pictures. */
private fun renderAiConversation(ctx: Context, msgs: List<AiSnapMsg>, name: String, c: AiSnapColors): List<Bitmap> {
    val w = 1080
    val pad = 56f
    val avatar = 72f
    val avatarGap = 16f
    val padX = 40f
    val padY = 30f
    val gap = 26f
    val headerH = 112f + 36f
    val maxPageH = 3600f
    val maxPages = 6
    val maxBubble = 800f
    val light = ResourcesCompat.getFont(ctx, R.font.amman_light) ?: Typeface.DEFAULT
    val bold = ResourcesCompat.getFont(ctx, R.font.amman_bold) ?: Typeface.DEFAULT_BOLD
    val candyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = c.text; typeface = light; textSize = 42f }
    val minePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = c.mineText; typeface = light; textSize = 42f }

    fun build(body: CharSequence, paint: TextPaint, width: Int) = StaticLayout.Builder.obtain(body, 0, body.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setLineSpacing(0f, 1.3f)
        .setIncludePad(false)
        .build()

    val measured = msgs.filter { it.text.isNotBlank() }.map { m ->
        val body = snapText(m.text.trim(), bold)
        val paint = if (m.mine) minePaint else candyPaint
        val inner = (maxBubble - 2 * padX).toInt()
        var lay = build(body, paint, inner)
        var widest = 0f
        for (i in 0 until lay.lineCount) widest = maxOf(widest, lay.getLineWidth(i))
        val fit = Math.ceil(widest.toDouble()).toInt().coerceIn(1, inner)
        if (fit < inner) lay = build(body, paint, fit)
        AiMeasured(m.mine, lay, fit + 2 * padX, lay.height + 2 * padY)
    }

    // group into pages by height, then keep the latest ones
    val pages = ArrayList<List<AiMeasured>>()
    var cur = ArrayList<AiMeasured>()
    var curH = 0f
    for (m in measured) {
        if (cur.isNotEmpty() && curH + gap + m.bubbleH > maxPageH) {
            pages.add(cur)
            cur = ArrayList()
            curH = 0f
        }
        curH += (if (cur.isEmpty()) 0f else gap) + m.bubbleH
        cur.add(m)
    }
    if (cur.isNotEmpty()) pages.add(cur)
    val keep = pages.takeLast(maxPages)

    val photo = BitmapFactory.decodeResource(ctx.resources, R.drawable.candy_ai_avatar)
    val bmps = ArrayList<Bitmap>()
    for ((pi, page) in keep.withIndex()) {
        val first = pi == 0
        val contentH = page.sumOf { it.bubbleH.toDouble() }.toFloat() + gap * (page.size - 1)
        val h = (pad + (if (first) headerH else 0f) + contentH + pad).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(c.bg)
        var y = pad
        if (first) {
            if (photo != null) {
                val circle = Path().apply { addCircle(pad + 56f, y + 56f, 56f, Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(circle)
                canvas.drawBitmap(photo, null, RectF(pad, y, pad + 112f, y + 112f), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                canvas.restore()
            }
            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.text; typeface = bold; textSize = 52f }
            canvas.drawText(name, pad + 112f + 28f, y + 56f - (namePaint.descent() + namePaint.ascent()) / 2, namePaint)
            y += headerH
        }
        for (m in page) {
            val left = if (m.mine) w - pad - m.bubbleW else pad + avatar + avatarGap
            val rect = RectF(left, y, left + m.bubbleW, y + m.bubbleH)
            val r = 44f
            val small = 12f
            val radii = if (m.mine) floatArrayOf(r, r, r, r, small, small, r, r) else floatArrayOf(r, r, r, r, r, r, small, small)
            val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (m.mine) c.accent else c.bubble }
            canvas.drawPath(Path().apply { addRoundRect(rect, radii, Path.Direction.CW) }, bubble)
            canvas.save()
            canvas.translate(left + padX, y + padY)
            m.layout.draw(canvas)
            canvas.restore()
            if (!m.mine && photo != null) {
                val top = y + m.bubbleH - avatar
                val circle = Path().apply { addCircle(pad + avatar / 2, top + avatar / 2, avatar / 2, Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(circle)
                canvas.drawBitmap(photo, null, RectF(pad, top, pad + avatar, top + avatar), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                canvas.restore()
            }
            y += m.bubbleH + gap
        }
        bmps.add(bmp)
    }
    photo?.recycle()
    return bmps
}

/** Makes the pictures, saves them and opens the share sheet of the phone. Returns false when that could not be done. */
private suspend fun shareAiPictures(ctx: Context, make: () -> List<Bitmap>): Boolean {
    val files = withContext(Dispatchers.IO) {
        try {
            val bmps = make()
            val dir = File(ctx.cacheDir, "share").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() }
            }
            val stamp = System.currentTimeMillis()
            bmps.mapIndexed { i, b ->
                val f = File(dir, "candy_${stamp}_$i.png")
                f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
                f
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
    if (files.isNullOrEmpty()) return false
    val uris = files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", it) }
    val clip = ClipData.newRawUri("", uris[0]).also { cd -> for (u in uris.drop(1)) cd.addItem(ClipData.Item(u)) }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
    }
    send.type = "image/png"
    send.clipData = clip
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        ctx.startActivity(chooser)
        true
    } catch (e: Exception) {
        false
    }
}

/** Shares one reply of Candy as a picture. */
internal suspend fun shareAiReply(ctx: Context, text: String, colors: AiSnapColors): Boolean =
    shareAiPictures(ctx) { listOf(renderAiSnapshot(ctx, text.trim(), ctx.getString(R.string.ai_name), colors)) }

/** Shares the whole conversation as picture(s). */
internal suspend fun shareAiConversation(ctx: Context, msgs: List<AiSnapMsg>, colors: AiSnapColors): Boolean =
    shareAiPictures(ctx) { renderAiConversation(ctx, msgs, ctx.getString(R.string.ai_name), colors) }
