package com.riddle.diary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import kotlin.math.ceil
import kotlin.math.max

/**
 * Tom's handwriting: rasterize Dancing Script, Zhang-Suen thin, trace strokes.
 * Ported from the original Riddle Rust implementation.
 */
class ReplyPlanner(context: Context) {
    private val typeface: Typeface = ResourcesCompat.getFont(context, R.font.dancing_script)
        ?: Typeface.SERIF
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = REPLY_PX
        this.typeface = this@ReplyPlanner.typeface
    }

    fun plan(text: String, marginX: Int, startY: Int, maxWidth: Int, canvasHeight: Int): WritePlan {
        val lines = wrap(text, maxWidth)
        val strokes = mutableListOf<List<Pair<Int, Int>>>()
        val region = BoundingBox()
        var y = startY

        for (line in lines) {
            val raster = rasterizeLine(line)
            thin(raster)
            val traced = trace(raster)
            for (stroke in traced) {
                val offset = stroke.map { (x, py) -> (x + marginX) to (y + py) }
                strokes.add(offset)
                offset.forEach { (x, py) -> region.add(x, py, 4) }
            }
            y += raster.height + 24
        }

        return WritePlan(
            strokes = strokes,
            region = region,
            nextY = y.coerceAtMost(canvasHeight - 40),
        )
    }

    fun append(existing: WritePlan, moreText: String, marginX: Int, maxWidth: Int, canvasHeight: Int): WritePlan {
        val addition = plan(moreText, marginX, existing.nextY, maxWidth, canvasHeight)
        return existing.copy(
            strokes = existing.strokes + addition.strokes,
            region = mergeBounds(existing.region, addition.region),
            nextY = addition.nextY,
        )
    }

    private fun wrap(text: String, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        for (para in text.lines()) {
            var current = ""
            for (word in para.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (textPaint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                    current = candidate
                } else {
                    lines.add(current)
                    current = word
                }
            }
            if (current.isNotEmpty()) lines.add(current)
        }
        return lines.ifEmpty { listOf(text) }
    }

    private data class RasterLine(val width: Int, val height: Int, val mask: BooleanArray)

    private fun rasterizeLine(text: String): RasterLine {
        val width = max(1, ceil(textPaint.measureText(text)).toInt() + 8)
        val fm = textPaint.fontMetrics
        val height = max(1, ceil(fm.descent - fm.top).toInt() + 8)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(text, 4f, 4f - fm.top, textPaint)

        val mask = BooleanArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                mask[y * width + x] = Color.red(bitmap.getPixel(x, y)) < 200
            }
        }
        return RasterLine(width, height, mask)
    }

    private fun thin(line: RasterLine) {
        val w = line.width
        val h = line.height
        val mask = line.mask
        fun idx(x: Int, y: Int) = y * w + x

        while (true) {
            var changed = false
            for (phase in 0..1) {
                val toClear = mutableListOf<Int>()
                for (y in 1 until h - 1) {
                    for (x in 1 until w - 1) {
                        if (!mask[idx(x, y)]) continue
                        val p = booleanArrayOf(
                            mask[idx(x, y - 1)],
                            mask[idx(x + 1, y - 1)],
                            mask[idx(x + 1, y)],
                            mask[idx(x + 1, y + 1)],
                            mask[idx(x, y + 1)],
                            mask[idx(x - 1, y + 1)],
                            mask[idx(x - 1, y)],
                            mask[idx(x - 1, y - 1)],
                        )
                        val b = p.count { it }
                        if (b !in 2..6) continue
                        var transitions = 0
                        for (i in 0 until 8) {
                            if (!p[i] && p[(i + 1) % 8]) transitions++
                        }
                        if (transitions != 1) continue
                        val (c1, c2) = if (phase == 0) {
                            !(p[0] && p[2] && p[4]) to !(p[2] && p[4] && p[6])
                        } else {
                            !(p[0] && p[2] && p[6]) to !(p[0] && p[4] && p[6])
                        }
                        if (c1 && c2) toClear.add(idx(x, y))
                    }
                }
                if (toClear.isNotEmpty()) {
                    changed = true
                    toClear.forEach { mask[it] = false }
                }
            }
            if (!changed) break
        }
    }

    private fun trace(line: RasterLine): List<List<Pair<Int, Int>>> {
        val w = line.width
        val h = line.height
        val mask = line.mask
        fun at(x: Int, y: Int): Boolean =
            x in 0 until w && y in 0 until h && mask[y * w + x]

        fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> {
            val out = mutableListOf<Pair<Int, Int>>()
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if ((dx != 0 || dy != 0) && at(x + dx, y + dy)) out.add(x + dx to y + dy)
                }
            }
            return out
        }

        val visited = BooleanArray(w * h)
        fun seen(x: Int, y: Int) = visited[y * w + x]
        fun mark(x: Int, y: Int) { visited[y * w + x] = true }

        val starts = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (at(x, y) && neighbors(x, y).size == 1) starts.add(x to y)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (at(x, y)) starts.add(x to y)
            }
        }

        val strokes = mutableListOf<List<Pair<Int, Int>>>()
        for ((sx, sy) in starts) {
            if (seen(sx, sy)) continue
            val path = mutableListOf(sx to sy)
            mark(sx, sy)
            var cx = sx
            var cy = sy
            while (true) {
                val next = neighbors(cx, cy).firstOrNull { !seen(it.first, it.second) } ?: break
                mark(next.first, next.second)
                path.add(next)
                cx = next.first
                cy = next.second
            }
            if (path.size >= 3) strokes.add(path)
        }
        return strokes.sortedBy { stroke -> stroke.minOf { it.first } }
    }

    private fun mergeBounds(a: BoundingBox, b: BoundingBox): BoundingBox {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return BoundingBox(
            left = minOf(a.left, b.left),
            top = minOf(a.top, b.top),
            right = maxOf(a.right, b.right),
            bottom = maxOf(a.bottom, b.bottom),
        )
    }

    companion object {
        private const val REPLY_PX = 96f
    }
}