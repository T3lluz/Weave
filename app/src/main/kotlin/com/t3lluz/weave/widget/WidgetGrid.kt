package com.t3lluz.weave.widget

import kotlin.math.max
import kotlin.math.min

/**
 * How a widget lays out its shortcuts: how many tiles stand in each row (each row fills the
 * width with its own), how big their pictures are, and whether there is room for their names.
 */
internal data class WidgetLayout(val rows: List<Int>, val faceDp: Int, val labels: Boolean, val bigText: Boolean)

/**
 * The layout for [count] shortcuts in a widget of [widthDp] by [heightDp]: the split into rows
 * that leaves the tiles closest to a comfortable shape, a little wider than tall. Fewer
 * shortcuts get bigger tiles and bigger pictures; names go when a tile is too small for one.
 * On a Pixel 10 a 4 × 2 widget (373 × 200 dp) holds two side by side, three as columns, four as
 * 2 × 2, five as 3 + 2, six as 3 × 2, seven as 4 + 3 and eight as 4 × 2.
 */
internal fun widgetLayout(count: Int, widthDp: Float, heightDp: Float): WidgetLayout {
    val n = count.coerceIn(1, MAX_SHORTCUTS)
    val w = max(widthDp - 2 * PAD_DP, 1f)
    val h = max(heightDp - 2 * PAD_DP, 1f)
    var best: WidgetLayout? = null
    var bestScore = -1f
    for (r in 1..n) {
        val cols = (n + r - 1) / r
        val tw = (w - GAP_DP * (cols - 1)) / cols
        val th = (h - GAP_DP * (r - 1)) / r
        val labels = tw >= LABEL_MIN_W_DP && th >= LABEL_MIN_H_DP
        val shape = (tw / th) / NICE_SHAPE
        var score = min(shape, 1f / shape)
        if (n % r != 0) score -= UNEVEN_COST          // a short last row is a small price
        if (tw < TILE_MIN_DP || th < TILE_MIN_DP) score -= 1f   // too small to hit: only if nothing else is left
        if (score > bestScore) {
            bestScore = score
            val face = (min(tw, th - if (labels) LABEL_ROOM_DP else 0f) * FACE_SHARE).toInt().coerceIn(FACE_MIN_DP, FACE_MAX_DP)
            // the fuller rows first: five are three over two
            val counts = List(r) { i -> n / r + if (i < n % r) 1 else 0 }
            best = WidgetLayout(counts, face, labels, bigText = face >= BIG_TEXT_FACE_DP)
        }
    }
    return best ?: WidgetLayout(listOf(n), 28, labels = true, bigText = false)
}

/** [shown] cut into the rows of [layout], in order. */
internal fun <T> widgetRows(shown: List<T>, layout: WidgetLayout): List<List<T>> {
    var from = 0
    return layout.rows.map { count -> shown.subList(from, (from + count).coerceAtMost(shown.size)).also { from += count } }
}

/**
 * The shortcuts a widget shows: the ones picked for it, in the order picked, leaving out any
 * since deleted; with none picked (or none of them left), the shelf's own order. Never more
 * than [MAX_SHORTCUTS].
 */
internal fun <T> widgetPicks(all: List<T>, picked: List<String>, id: (T) -> String): List<T> {
    val byId = all.associateBy(id)
    val chosen = picked.mapNotNull { byId[it] }
    return (chosen.ifEmpty { all }).take(MAX_SHORTCUTS)
}

/** A widget holds between two and eight shortcuts (one, when one is all there is). */
internal const val MIN_SHORTCUTS = 2
internal const val MAX_SHORTCUTS = 8

internal const val PAD_DP = 8f
internal const val GAP_DP = 6f
private const val NICE_SHAPE = 1.2f
private const val UNEVEN_COST = 0.15f
private const val LABEL_MIN_W_DP = 56f
private const val LABEL_MIN_H_DP = 60f
private const val LABEL_ROOM_DP = 22f
private const val FACE_SHARE = 0.42f
private const val TILE_MIN_DP = 40f
private const val FACE_MIN_DP = 20
private const val FACE_MAX_DP = 44
private const val BIG_TEXT_FACE_DP = 38
