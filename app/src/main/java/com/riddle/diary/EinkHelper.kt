package com.riddle.diary

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View

/**
 * Onyx EPD refresh with coalescing. E-ink panels cannot keep up with per-point
 * updates — batching partial refreshes is critical for pen latency.
 */
class EinkHelper(private val view: View) {
    private val onyxAvailable: Boolean
    private val pendingDirty = Rect()
    private var penMode = false
    private var fastModePinned = false
    private var lastRefreshAt = 0L

    init {
        var available = false
        try {
            val clazz = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            clazz.getMethod("applyApplicationFastMode", Boolean::class.javaPrimitiveType)
                .invoke(null, true)
            available = true
        } catch (e: Throwable) {
            Log.i(TAG, "Onyx EPD controller unavailable; using standard refresh")
        }
        onyxAvailable = available
    }

    fun enterPenMode() {
        penMode = true
        pinFastMode(true)
    }

    fun leavePenMode() {
        penMode = false
        flush(fast = true)
        pinFastMode(false)
    }

    fun queueRefresh(rect: Rect, fast: Boolean = true) {
        if (rect.isEmpty) return
        if (pendingDirty.isEmpty) {
            pendingDirty.set(rect)
        } else {
            pendingDirty.union(rect)
        }
        val interval = if (penMode) PEN_REFRESH_MS else NORMAL_REFRESH_MS
        val now = SystemClock.uptimeMillis()
        if (now - lastRefreshAt >= interval) {
            flush(fast)
        }
    }

    fun flush(fast: Boolean = true) {
        if (pendingDirty.isEmpty) return
        refreshRegionImmediate(Rect(pendingDirty), fast)
        pendingDirty.setEmpty()
        lastRefreshAt = SystemClock.uptimeMillis()
    }

    fun refreshRegion(rect: Rect, fast: Boolean = true) = queueRefresh(rect, fast)

    fun fullRefresh() {
        pendingDirty.setEmpty()
        if (!onyxAvailable) {
            view.postInvalidate()
            return
        }
        try {
            setUpdateMode(quality = true)
            view.postInvalidate()
        } catch (e: Throwable) {
            view.postInvalidate()
        }
    }

    private fun refreshRegionImmediate(rect: Rect, fast: Boolean) {
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        if (!onyxAvailable) {
            view.postInvalidate(l, t, r, b)
            return
        }
        try {
            if (!fastModePinned) {
                setUpdateMode(quality = !fast)
            }
            view.postInvalidate(l, t, r, b)
        } catch (e: Throwable) {
            view.postInvalidate(l, t, r, b)
        }
    }

    private fun pinFastMode(enabled: Boolean) {
        if (!onyxAvailable) return
        fastModePinned = enabled
        try {
            setUpdateMode(quality = !enabled)
        } catch (_: Throwable) {
        }
    }

    private fun setUpdateMode(quality: Boolean) {
        val modeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode")
        val mode = if (quality) {
            modeClass.getField("GC").get(null)
        } else {
            modeClass.getField("GU").get(null)
        }
        val epd = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
        val setMode = epd.getMethod("setViewDefaultUpdateMode", View::class.java, modeClass)
        setMode.invoke(null, view, mode)
    }

    companion object {
        private const val TAG = "EinkHelper"
        private const val PEN_REFRESH_MS = 50L
        private const val NORMAL_REFRESH_MS = 16L
    }
}