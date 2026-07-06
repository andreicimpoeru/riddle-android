package com.riddle.diary

import android.graphics.Rect

data class PenSample(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val erasing: Boolean,
    val touching: Boolean,
)

data class BoundingBox(
    var left: Int = Int.MAX_VALUE,
    var top: Int = Int.MAX_VALUE,
    var right: Int = Int.MIN_VALUE,
    var bottom: Int = Int.MIN_VALUE,
) {
    fun isEmpty(): Boolean = left > right

    fun add(x: Int, y: Int, margin: Int = 0) {
        left = minOf(left, x - margin)
        top = minOf(top, y - margin)
        right = maxOf(right, x + margin)
        bottom = maxOf(bottom, y + margin)
    }

    fun rect(): Rect = Rect(left, top, right + 1, bottom + 1)

    fun clear() {
        left = Int.MAX_VALUE
        top = Int.MAX_VALUE
        right = Int.MIN_VALUE
        bottom = Int.MIN_VALUE
    }
}

sealed class DiaryState {
    data class Listening(val lastPenUpAt: Long? = null) : DiaryState()

    data class Drinking(
        val stage: Int,
        val nextAt: Long,
        val region: BoundingBox,
    ) : DiaryState()

    data class Thinking(val startedAt: Long) : DiaryState()

    data class Replying(
        val plan: WritePlan,
        val nextAt: Long,
        val pendingText: String = "",
    ) : DiaryState()

    data class Lingering(val until: Long, val region: BoundingBox) : DiaryState()

    data class FadingReply(
        val stage: Int,
        val nextAt: Long,
        val region: BoundingBox,
    ) : DiaryState()
}

data class WritePlan(
    val strokes: List<List<Pair<Int, Int>>>,
    var strokeIndex: Int = 0,
    var pointIndex: Int = 0,
    val region: BoundingBox,
    var nextY: Int,
)