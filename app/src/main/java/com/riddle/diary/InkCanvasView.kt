package com.riddle.diary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val erasePaint = Paint(inkPaint).apply { color = Color.WHITE }

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    private val strokes = mutableListOf<MutableList<Triple<Int, Int, Int>>>()
    private var currentStroke = mutableListOf<Triple<Int, Int, Int>>()
    private var lastErase: Pair<Int, Int>? = null
    val inkBounds = BoundingBox()

    var onInkChanged: ((Rect) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bitmap = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap!!)
        clearPage()
    }

    fun clearPage() {
        canvas?.drawColor(Color.WHITE)
        strokes.clear()
        currentStroke.clear()
        lastErase = null
        inkBounds.clear()
        invalidate()
    }

    fun hasInk(): Boolean = strokes.isNotEmpty() || currentStroke.isNotEmpty()

    fun strokeList(): List<List<Triple<Int, Int, Int>>> = strokes

    fun penPoint(x: Float, y: Float, pressure: Float): Rect {
        val ix = x.roundToInt()
        val iy = y.roundToInt()
        val radius = (2f + pressure * 3f).roundToInt().coerceAtLeast(2)
        val dirty = BoundingBox()
        val c = canvas ?: return Rect()

        if (currentStroke.isNotEmpty()) {
            val (px, py, pr) = currentStroke.last()
            drawLine(c, px, py, ix, iy, max(radius, pr + 1), inkPaint)
            dirty.add(px, py, pr + 2)
        } else {
            stamp(c, ix, iy, radius, inkPaint)
        }
        dirty.add(ix, iy, radius + 2)
        currentStroke.add(Triple(ix, iy, radius))
        inkBounds.add(ix, iy, radius + 2)
        postDirty(dirty.rect())
        return dirty.rect()
    }

    fun erasePoint(x: Float, y: Float): Rect {
        val ix = x.roundToInt()
        val iy = y.roundToInt()
        val radius = 22
        val dirty = BoundingBox()
        val c = canvas ?: return Rect()

        lastErase?.let { (px, py) ->
            drawLine(c, px, py, ix, iy, radius, erasePaint)
            dirty.add(px, py, radius + 2)
        } ?: stamp(c, ix, iy, radius, erasePaint)
        dirty.add(ix, iy, radius + 2)
        lastErase = ix to iy
        postDirty(dirty.rect())
        return dirty.rect()
    }

    fun penUp() {
        if (currentStroke.isNotEmpty()) {
            strokes.add(currentStroke)
            currentStroke = mutableListOf()
        }
        lastErase = null
    }

    fun clearInkData() {
        strokes.clear()
        currentStroke.clear()
        lastErase = null
        inkBounds.clear()
    }

    fun drawReplyPoint(x: Int, y: Int, radius: Int = 2) {
        val dirty = BoundingBox()
        stamp(canvas ?: return, x, y, radius, inkPaint)
        dirty.add(x, y, radius + 2)
        postDirty(dirty.rect())
    }

    fun drawReplyLine(x0: Int, y0: Int, x1: Int, y1: Int, radius: Int = 2) {
        val dirty = BoundingBox()
        drawLine(canvas ?: return, x0, y0, x1, y1, radius, inkPaint)
        dirty.add(x0, y0, radius + 2)
        dirty.add(x1, y1, radius + 2)
        postDirty(dirty.rect())
    }

    fun fillRect(rect: Rect, color: Int) {
        canvas?.drawRect(rect, Paint().apply { this.color = color })
        postDirty(rect)
    }

    fun dissolvePass(region: BoundingBox, stage: Int, stages: Int) {
        if (region.isEmpty()) return
        val bmp = bitmap ?: return
        val c = canvas ?: return
        val paint = Paint().apply { this.color = Color.WHITE }
        val dirty = BoundingBox()

        for (y in region.top..region.bottom) {
            for (x in region.left..region.right) {
                if (x !in 0 until width || y !in 0 until height) continue
                val pixel = bmp.getPixel(x, y)
                if (Color.red(pixel) < 250 && pxHash(x, y) % stages <= stage) {
                    c.drawPoint(x.toFloat(), y.toFloat(), paint)
                    dirty.add(x, y, 0)
                }
            }
        }
        if (!dirty.isEmpty()) postDirty(dirty.rect())
    }

    fun toPngBytes(): ByteArray {
        val bmp = bitmap ?: error("no bitmap")
        if (inkBounds.isEmpty()) error("no ink")

        val pad = 20
        val x0 = max(0, inkBounds.left - pad)
        val y0 = max(0, inkBounds.top - pad)
        val x1 = min(width, inkBounds.right + pad + 1)
        val y1 = min(height, inkBounds.bottom + pad + 1)
        val cropW = x1 - x0
        val cropH = y1 - y0
        val scale = max(2, max(cropW, cropH).divCeiling(1200))

        val outW = cropW / scale
        val outH = cropH / scale
        val scaled = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bmp, x0, y0, cropW, cropH),
            outW,
            outH,
            true,
        )
        val gray = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(gray)
        val paint = Paint()
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val luma = Color.red(scaled.getPixel(x, y))
                paint.color = Color.rgb(luma, luma, luma)
                c.drawPoint(x.toFloat(), y.toFloat(), paint)
            }
        }
        val stream = ByteArrayOutputStream()
        gray.compress(Bitmap.CompressFormat.PNG, 90, stream)
        return stream.toByteArray()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun postDirty(rect: Rect) {
        invalidate(rect)
        onInkChanged?.invoke(rect)
    }

    private fun stamp(c: Canvas, x: Int, y: Int, r: Int, paint: Paint) {
        c.drawCircle(x.toFloat(), y.toFloat(), r.toFloat(), paint)
    }

    private fun drawLine(c: Canvas, x0: Int, y0: Int, x1: Int, y1: Int, r: Int, paint: Paint) {
        paint.strokeWidth = (r * 2).toFloat()
        paint.style = Paint.Style.STROKE
        c.drawLine(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paint)
        paint.style = Paint.Style.FILL
    }

    private fun pxHash(x: Int, y: Int): Int {
        var h = x.toUInt() * 0x9E3779B1u xor y.toUInt() * 0x85EBCA6Bu
        h = h xor (h shr 13)
        h *= 0xC2B2AE35u
        h = h xor (h shr 16)
        return h.toInt().and(Int.MAX_VALUE)
    }

    private fun Int.divCeiling(other: Int): Int = (this + other - 1) / other
}