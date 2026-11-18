package com.t3lluz.weave.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.t3lluz.weave.data.link.LinkShortcut
import com.t3lluz.weave.data.link.LinkShortcutKind
import com.t3lluz.weave.data.link.LinkShortcutStore
import com.t3lluz.weave.ui.screens.link.pictureTone
import com.t3lluz.weave.ui.theme.Background
import com.t3lluz.weave.ui.theme.LinkShortcutTone
import kotlin.math.min

/** A widget tile's picture, and the colour the tile wears with it. */
internal class WidgetFace(val picture: Bitmap, val tone: Int)

/**
 * Draws what the shelf's tiles show, as bitmaps a widget can hold: a shortcut's own glyph in its
 * colour (with its app's icon on the corner), else its first step's app or site icon, a keycap
 * for keys, "Aa" for text. Called off the main thread.
 */
internal object WidgetFaces {
    suspend fun face(context: Context, store: LinkShortcutStore, s: LinkShortcut, px: Int): WidgetFace {
        val glyph = s.shownGlyph
        val lead = s.lead
        val pictured = lead?.takeIf { it.kind == LinkShortcutKind.APP || it.kind == LinkShortcutKind.SITE }
        val file = pictured?.let(store::iconFile)
        val tone = glyph?.color?.toInt() ?: file?.let(::pictureTone)?.toArgb() ?: LinkShortcutTone.toArgb()
        val picture = when {
            glyph != null -> glyph(context, store, glyph.id, tone, px)?.let { badge(it, s.appId?.let(store::appIconFile), px) }
            file != null -> BitmapFactory.decodeFile(file.path)?.let { fit(it, px, round = lead.kind == LinkShortcutKind.SITE) }
            lead?.kind == LinkShortcutKind.KEYS && lead.key.isNotBlank() -> keycap(lead.cap.ifBlank { lead.key }, tone, px)
            lead?.kind == LinkShortcutKind.TEXT -> keycap("Aa", tone, px)
            else -> null
        } ?: keycap(s.shownLabel.trim().take(1).uppercase().ifEmpty { "?" }, tone, px)
        return WidgetFace(picture, tone)
    }

    /** An Iconify glyph from disk (fetched once if it isn't there yet), in [tone]. */
    private suspend fun glyph(context: Context, store: LinkShortcutStore, id: String, tone: Int, px: Int): Bitmap? {
        val file = store.glyphFile(id) ?: run {
            store.fetchGlyph(id)
            store.glyphFile(id)
        } ?: return null
        val request = ImageRequest.Builder(context).data(file).decoderFactory(SvgDecoder.Factory()).size(px).allowHardware(false).build()
        val drawn = ImageLoader(context).execute(request).drawable?.toBitmap(px, px) ?: return null
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(drawn, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = PorterDuffColorFilter(tone, PorterDuff.Mode.SRC_IN) })
        return out
    }

    /** The glyph with its app's icon on its lower right corner, ringed in the tile's dark. */
    private fun badge(glyph: Bitmap, app: java.io.File?, px: Int): Bitmap {
        val icon = app?.let { BitmapFactory.decodeFile(it.path) } ?: return glyph
        val room = (px * 0.16f).toInt()
        val out = Bitmap.createBitmap(px + room, px + room, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(glyph, room / 2f, room / 2f, null)
        val d = px * 0.5f
        val box = RectF(out.width - d, out.height - d, out.width.toFloat(), out.height.toFloat())
        canvas.drawOval(box, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Background.toArgb() })
        val inset = px * 0.05f
        canvas.drawBitmap(icon, null, RectF(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** A picture scaled into a square of [px], a site's with its corners rounded as on the shelf. */
    private fun fit(source: Bitmap, px: Int, round: Boolean): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val box = RectF(0f, 0f, px.toFloat(), px.toFloat())
        if (round) canvas.clipPath(Path().apply { addRoundRect(box, px * 0.24f, px * 0.24f, Path.Direction.CW) })
        val scale = min(px / source.width.toFloat(), px / source.height.toFloat())
        val w = source.width * scale
        val h = source.height * scale
        canvas.drawBitmap(source, null, RectF((px - w) / 2f, (px - h) / 2f, (px + w) / 2f, (px + h) / 2f), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** A key as the shelf draws it: its name in a rounded outline, both in [tone]. */
    private fun keycap(text: String, tone: Int, px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val stroke = px * 0.065f
        val box = RectF(stroke, px * 0.14f, px - stroke, px * 0.86f)
        canvas.drawRoundRect(box, px * 0.2f, px * 0.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tone; alpha = 46 })
        canvas.drawRoundRect(box, px * 0.2f, px * 0.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tone; style = Paint.Style.STROKE; strokeWidth = stroke; alpha = 150 })
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tone
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = px * 0.42f
        }
        // a long name ("Enter", "Space") shrinks to stay inside its cap
        val wide = ink.measureText(text)
        val room = box.width() - px * 0.18f
        if (wide > room) ink.textSize *= room / wide
        canvas.drawText(text, px / 2f, px / 2f - (ink.descent() + ink.ascent()) / 2f, ink)
        return out
    }
}
