package com.riddle.diary

import android.graphics.Rect
import android.util.Log
import android.view.View

/**
 * Wraps Onyx EPD refresh calls when running on Boox hardware.
 * Falls back to standard View invalidation elsewhere.
 */
class EinkHelper(private val view: View) {
    private val onyxAvailable: Boolean
    private var epdController: Any? = null

    init {
        var available = false
        try {
            val clazz = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            val method = clazz.getMethod("applyApplicationFastMode", Boolean::class.javaPrimitiveType)
            method.invoke(null, true)
            epdController = clazz
            available = true
        } catch (e: Throwable) {
            Log.i(TAG, "Onyx EPD controller unavailable; using standard refresh")
        }
        onyxAvailable = available
    }

    fun refreshRegion(rect: Rect, fast: Boolean = true) {
        if (!onyxAvailable) {
            view.postInvalidate(rect.left, rect.top, rect.right, rect.bottom)
            return
        }
        try {
            val modeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode")
            val mode = if (fast) {
                modeClass.getField("GU").get(null)
            } else {
                modeClass.getField("GC").get(null)
            }
            val epd = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            val setMode = epd.getMethod("setViewDefaultUpdateMode", View::class.java, modeClass)
            setMode.invoke(null, view, mode)
            view.postInvalidate(rect.left, rect.top, rect.right, rect.bottom)
        } catch (e: Throwable) {
            view.postInvalidate(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    fun fullRefresh() {
        if (!onyxAvailable) {
            view.postInvalidate()
            return
        }
        try {
            val modeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode")
            val mode = modeClass.getField("GC").get(null)
            val epd = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            val setMode = epd.getMethod("setViewDefaultUpdateMode", View::class.java, modeClass)
            setMode.invoke(null, view, mode)
            view.postInvalidate()
        } catch (e: Throwable) {
            view.postInvalidate()
        }
    }

    companion object {
        private const val TAG = "EinkHelper"
    }
}