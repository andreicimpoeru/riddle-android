package com.riddle.diary

import kotlin.math.abs
import kotlin.math.atan2

object HelpDetector {
    fun looksLikeQuestionMark(strokes: List<List<Triple<Int, Int, Int>>>): Boolean {
        if (strokes.size != 1) return false
        val points = strokes.first().map { it.first.toFloat() to it.second.toFloat() }
        if (points.size < 12) return false

        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val width = (xs.maxOrNull()!! - xs.minOrNull()!!)
        val height = (ys.maxOrNull()!! - ys.minOrNull()!!)
        if (width < 80 || height < 120) return false
        if (height < width * 1.2f) return false

        val cx = xs.average().toFloat()
        val cy = ys.average().toFloat()
        val maxR = points.maxOf { hypot(it.first - cx, it.second - cy) }
        if (maxR < 50f) return false

        var arc = 0f
        for (i in 1 until points.size) {
            val a0 = atan2(points[i - 1].second - cy, points[i - 1].first - cx)
            val a1 = atan2(points[i].second - cy, points[i].first - cx)
            var d = a1 - a0
            while (d > kotlin.math.PI) d -= (2 * kotlin.math.PI).toFloat()
            while (d < -kotlin.math.PI) d += (2 * kotlin.math.PI).toFloat()
            arc += abs(d)
        }
        return arc > 4.5f
    }

    private fun hypot(x: Float, y: Float): Float {
        return kotlin.math.hypot(x, y)
    }
}