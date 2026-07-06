package com.riddle.diary

import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * Pen input for Boox via Onyx TouchHelper.
 *
 * When Onyx is available we enable native raw drawing render so the vendor
 * e-ink path shows ink with minimal latency, while stroke points are recorded
 * and committed to our bitmap on pen-up for OCR export.
 */
class PenController(
    private val view: View,
    private val onSample: (PenSample) -> Unit,
    private val onStrokeStart: (Boolean) -> Unit = { _ -> },
    private val onStrokeEnd: (Boolean) -> Unit = { _ -> },
) {
    private var touchHelper: Any? = null
    var nativeRenderActive: Boolean = false
        private set

    fun attach() {
        if (tryAttachOnyx()) return
        Log.i(TAG, "Using standard stylus MotionEvent fallback")
    }

    fun detach() {
        if (touchHelper == null) return
        try {
            touchHelper?.javaClass?.getMethod("closeRawDrawing")?.invoke(touchHelper)
        } catch (_: Throwable) {
        }
        touchHelper = null
        nativeRenderActive = false
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (nativeRenderActive) return true
        if (!event.isStylus()) return false

        val erasing = event.isEraserButton()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onStrokeStart(erasing)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onStrokeEnd(erasing)
                onSample(
                    PenSample(event.x, event.y, 0f, erasing, touching = false),
                )
            }
            MotionEvent.ACTION_MOVE -> {
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
                    "onBeginRawDrawing" -> onStrokeStart(false)
                    "onEndRawDrawing" -> {
                        onStrokeEnd(false)
                        onSample(PenSample(0f, 0f, 0f, false, false))
                    }
                    "onRawDrawingTouchPointMoveReceived" -> {
                        val point = args?.getOrNull(0) ?: return@newProxyInstance null
                        emitOnyxPoint(point, touchPointClass, false)
                    }
                    "onRawDrawingTouchPointListReceived" -> Unit
                    "onBeginRawErasing" -> onStrokeStart(true)
                    "onEndRawErasing" -> {
                        onStrokeEnd(true)
                        onSample(PenSample(0f, 0f, 0f, true, false))
                    }
                    "onRawErasingTouchPointMoveReceived" -> {
                        val point = args?.getOrNull(0) ?: return@newProxyInstance null
                        emitOnyxPoint(point, touchPointClass, true)
                    }
                    "onRawErasingTouchPointListReceived" -> Unit
                }
                null
            }

            val create = touchHelperClass.getMethod("create", View::class.java, callbackClass)
            touchHelper = create.invoke(null, view, callback)
            val limit = Rect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
            touchHelper!!.javaClass.getMethod(
                "setLimitRect",
                Rect::class.java,
                java.util.List::class.java,
            ).invoke(touchHelper, limit, emptyList<Rect>())
            touchHelper!!.javaClass.getMethod("setStrokeWidth", Float::class.javaPrimitiveType)
                .invoke(touchHelper, 3f)
            touchHelper!!.javaClass.getMethod("openRawDrawing").invoke(touchHelper)
            touchHelper!!.javaClass.getMethod("setRawDrawingEnabled", Boolean::class.javaPrimitiveType)
                .invoke(touchHelper, true)
            // Let Onyx paint live ink on the fast e-ink path; we mirror strokes to
            // our bitmap on pen-up for OCR export.
            touchHelper!!.javaClass.getMethod(
                "setRawDrawingRenderEnabled",
                Boolean::class.javaPrimitiveType,
            ).invoke(touchHelper, true)

            nativeRenderActive = true
            Log.i(TAG, "Onyx TouchHelper active (native render enabled)")
            true
        } catch (e: Throwable) {
            Log.i(TAG, "Onyx TouchHelper unavailable: ${e.message}")
            false
        }
    }

    private fun emitOnyxPoint(point: Any, touchPointClass: Class<*>, erasing: Boolean) {
        val x = touchPointClass.getMethod("getX").invoke(point) as Float
        val y = touchPointClass.getMethod("getY").invoke(point) as Float
        val pressure = (touchPointClass.getMethod("getPressure").invoke(point) as Float)
            .coerceIn(0.05f, 1f)
        onSample(PenSample(x, y, pressure, erasing, touching = true))
    }

    private fun MotionEvent.isStylus(): Boolean {
        return getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
            getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun MotionEvent.isEraserButton(): Boolean {
        return getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
            buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 ||
            buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0
    }

    companion object {
        private const val TAG = "PenController"
    }
}