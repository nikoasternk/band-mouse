package com.band.mouse.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

class MouseAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MouseAccessibilityService? = null
            private set

        @Volatile
        var cursorSensitivity: Float = 2.8f

        @Volatile
        var scrollMultiplier: Float = 6.0f
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var cursorView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var cursorX = 500f
    private var cursorY = 1000f
    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onServiceConnected() {
        super.onServiceConnected()
        val prefs = getSharedPreferences("bandmouse_prefs", Context.MODE_PRIVATE)
        cursorSensitivity = prefs.getFloat("sensitivity", 2.8f)
        scrollMultiplier = prefs.getFloat("scroll_mult", 6.0f)
        instance = this
        setupOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun setupOverlay() {
        mainHandler.post {
            if (cursorView != null) return@post

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            cursorX = screenWidth / 2f
            cursorY = screenHeight / 2f

            cursorView = object : View(this) {
                private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }
                private val arrowPath = Path().apply {
                    moveTo(2f, 2f)
                    lineTo(2f, 42f)
                    lineTo(14f, 32f)
                    lineTo(24f, 50f)
                    lineTo(31f, 46f)
                    lineTo(21f, 28f)
                    lineTo(36f, 28f)
                    close()
                }

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    canvas.drawPath(arrowPath, fillPaint)
                    canvas.drawPath(arrowPath, strokePaint)
                }
            }

            layoutParams = WindowManager.LayoutParams(
                64,
                64,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = cursorX.toInt() + 2
                y = cursorY.toInt() + 2
            }

            try {
                wm.addView(cursorView, layoutParams)
            } catch (_: Exception) {}
        }
    }

    private fun removeOverlay() {
        mainHandler.post {
            cursorView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (_: Exception) {}
            }
            cursorView = null
        }
    }

    fun moveCursor(dx: Int, dy: Int) {
        mainHandler.post {
            val view = cursorView ?: return@post
            val wm = windowManager ?: return@post

            val speed = hypot(dx.toFloat(), dy.toFloat())
            val accel = (1.0f + (speed / 28f)).coerceAtMost(2.5f)
            val mult = cursorSensitivity * accel

            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels

            cursorX = (cursorX + dx * mult).coerceIn(0f, (screenWidth - 1).toFloat())
            cursorY = (cursorY + dy * mult).coerceIn(0f, (screenHeight - 1).toFloat())

            layoutParams.x = cursorX.toInt() + 2
            layoutParams.y = cursorY.toInt() + 2

            try {
                wm.updateViewLayout(view, layoutParams)
            } catch (_: Exception) {}
        }
    }

    fun performClick() {
        mainHandler.post {
            val path = Path().apply {
                moveTo(cursorX, cursorY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 45)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }
    }

    fun performScroll(dx: Int, dy: Int) {
        mainHandler.post {
            val startX = cursorX.coerceIn(100f, (screenWidth - 100).toFloat())
            val startY = cursorY.coerceIn(200f, (screenHeight - 200).toFloat())
            val endX = (startX + dx * scrollMultiplier).coerceIn(50f, (screenWidth - 50).toFloat())
            val endY = (startY + dy * scrollMultiplier).coerceIn(100f, (screenHeight - 100).toFloat())

            if (hypot(endX - startX, endY - startY) < 5f) return@post

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 130)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }
    }

    fun performBack() {
        mainHandler.post {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }
}