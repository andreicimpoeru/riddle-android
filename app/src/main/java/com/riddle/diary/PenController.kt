package com.riddle.diary

import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * Pen input for Boox via Onyx TouchHelper, with MotionEvent fallback for dev/emulator.
 */
class PenController(
    private val view: View,
    private val onSample: (PenSample) -> Unit,
) {
    private var touchHelper: Any? = null
    private var onyxActive = false

    fun attach() {
        if (tryAttachOnyx()) return
        Log.i(TAG, "Using standard stylus MotionEvent fallback")
    }

    fun detach() {
        if (!onyxActive) return
        try {
            val close = touchHelper?.javaClass?.getMethod("closeRawDrawing")
            close?.invoke(touchHelper)
        } catch (_: Throwable) {
        }
        touchHelper = null
        onyxActive = false
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (onyxActive) return true
        if (!event.isStylus()) return false

        val erasing = event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 ||
            event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                onSample(
                    PenSample(
                        x = event.x,
                        y = event.y,
                        pressure = event.pressure.coerceIn(0.05f, 1f),
                        erasing = erasing,
                        touching = true,
                    ),
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onSample(
                    PenSample(
                        x = event.x,
                        y = event.y,
                        pressure = 0f,
                        erasing = erasing,
                        touching = false,
                    ),
                )
            }
        }
        return true
    }

    private fun tryAttachOnyx(): Boolean {
        return try {
            val touchHelperClass = Class.forName("com.onyx.android.sdk.pen.TouchHelper")
            val callbackClass = Class.forName("com.onyx.android.sdk.pen.data.RawInputCallback")
            val touchPointClass = Class.forName("com.onyx.android.sdk.data.note.TouchPoint")

            val callback = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass),
            ) { _, method, args ->
                when (method.name) {
                    "onBeginRawDrawing" -> Unit
                    "onEndRawDrawing" -> onSample(
                        PenSample(0f, 0f, 0f, erasing = false, touching = false),
                    )
                    "onRawDrawingTouchPointMoveReceived" -> {
                        val point = args?.getOrNull(0) ?: return@newProxyInstance null
                        emitOnyxPoint(point, touchPointClass, erasing = false)
                    }
                    "onBeginRawErasing" -> Unit
                    "onEndRawErasing" -> onSample(
                        PenSample(0f, 0f, 0f, erasing = true, touching = false),
                    )
                    "onRawErasingTouchPointMoveReceived" -> {
                        val point = args?.getOrNull(0) ?: return@newProxyInstance null
                        emitOnyxPoint(point, touchPointClass, erasing = true)
                    }
                }
                null
            }

            val create = touchHelperClass.getMethod(
                "create",
                View::class.java,
                callbackClass,
            )
            touchHelper = create.invoke(null, view, callback)
            val limit = Rect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
            val setLimit = touchHelper!!.javaClass.getMethod(
                "setLimitRect",
                Rect::class.java,
                java.util.List::class.java,
            )
            setLimit.invoke(touchHelper, limit, emptyList<Rect>())
            touchHelper!!.javaClass.getMethod("setStrokeWidth", Float::class.javaPrimitiveType)
                .invoke(touchHelper, 3f)
            touchHelper!!.javaClass.getMethod("openRawDrawing").invoke(touchHelper)
            touchHelper!!.javaClass.getMethod("setRawDrawingEnabled", Boolean::class.javaPrimitiveType)
                .invoke(touchHelper, true)
            touchHelper!!.javaClass.getMethod(
                "setRawDrawingRenderEnabled",
                Boolean::class.javaPrimitiveType,
            ).invoke(touchHelper, false)

            onyxActive = true
            Log.i(TAG, "Onyx TouchHelper active")
            true
        } catch (e: Throwable) {
            Log.i(TAG, "Onyx TouchHelper unavailable: ${e.message}")
            false
        }
    }

    private fun emitOnyxPoint(point: Any, touchPointClass: Class<*>, erasing: Boolean) {
        val x = touchPointClass.getMethod("getX").invoke(point) as Float
        val y = touchPointClass.getMethod("getY").invoke(point) as Float
        val pressure = (touchPointClass.getMethod("getPressure").invoke(point) as Float).coerceIn(0.05f, 1f)
        onSample(PenSample(x, y, pressure, erasing, touching = true))
    }

    private fun MotionEvent.isStylus(): Boolean {
        return getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
            getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
    }

    companion object {
        private const val TAG = "PenController"
    }
}