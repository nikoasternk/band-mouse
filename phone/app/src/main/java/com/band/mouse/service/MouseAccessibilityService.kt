package com.band.mouse.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.band.mouse.MouseEngine
import com.band.mouse.transport.WearableMouseClient
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class MouseAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MouseAccessibilityService? = null
            private set

        @Volatile
        var cursorSensitivity: Float = 3.0f

        @Volatile
        var scrollMultiplier: Float = 8.0f

        @Volatile
        var isServiceMasterEnabled: Boolean = true

        @Volatile
        var isDragScrollEnabled: Boolean = true

        @Volatile
        var watchControlMode: String = "cursor"

        @Volatile
        var isWatchAppOpen: Boolean = false

        @Volatile
        var isStealthActive: Boolean = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var cursorView: View? = null
    private var stealthView: View? = null
    private lateinit var cursorParams: WindowManager.LayoutParams
    private lateinit var stealthParams: WindowManager.LayoutParams

    private var cursorX = 500f
    private var cursorY = 1000f
    private var targetX = 500f
    private var targetY = 1000f
    private var isAnimatingCursor = false
    private var isCursorDragging = false

    private var pendingScrollDx = 0f
    private var pendingScrollDy = 0f
    private var isScrollStrokeActive = false
    private var isScrollHolding = false
    private var activeScrollStroke: GestureDescription.StrokeDescription? = null
    private var scrollCurX = 540f
    private var scrollCurY = 1200f
    private var holdWobble = 0.4f

    private var screenWidth = 1080
    private var screenHeight = 2400

    private var savedBrightnessMode: Int = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    private var savedBrightnessValue: Int = 128
    private var isBrightnessOverridden: Boolean = false

    private val watchTimeoutRunnable = Runnable {
        isWatchAppOpen = false
        abortScrollSession()
        setStealthState(false)
    }

    private val scrollHoldTimeoutRunnable = Runnable {
        abortScrollSession()
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val view = cursorView
            val wm = windowManager
            if (view == null || wm == null || !isServiceMasterEnabled || !isWatchAppOpen || isStealthActive) {
                isAnimatingCursor = false
                return
            }

            val diffX = targetX - cursorX
            val diffY = targetY - cursorY

            if (abs(diffX) < 0.4f && abs(diffY) < 0.4f) {
                cursorX = targetX
                cursorY = targetY
                isAnimatingCursor = false
            } else {
                cursorX += diffX * 0.55f
                cursorY += diffY * 0.55f
                Choreographer.getInstance().postFrameCallback(this)
            }

            updateCursorLayoutPosition()
        }
    }

    private val scrollGestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            mainHandler.post {
                onContinuousScrollSliceCompleted()
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            mainHandler.post {
                activeScrollStroke = null
                isScrollStrokeActive = false
                if (isScrollHolding && hypot(pendingScrollDx, pendingScrollDy) >= 0.5f) {
                    startContinuousScrollStroke()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val prefs = getSharedPreferences("bandmouse_prefs", Context.MODE_PRIVATE)
        cursorSensitivity = prefs.getFloat("sensitivity", 3.0f)
        scrollMultiplier = prefs.getFloat("scroll_mult", 8.0f)
        isServiceMasterEnabled = prefs.getBoolean("master_enabled", true)
        isDragScrollEnabled = prefs.getBoolean("drag_scroll", true)
        instance = this
        setupOverlays()
        WearableMouseClient.getInstance(applicationContext).ensureListening()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post {
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            targetX = targetX.coerceIn(0f, (screenWidth - 1).toFloat())
            targetY = targetY.coerceIn(0f, (screenHeight - 1).toFloat())
            cursorX = targetX
            cursorY = targetY
            updateCursorLayoutPosition()

            if (::stealthParams.isInitialized) {
                val bounds = MouseEngine.computeStealthOverlayBounds(screenWidth, screenHeight)
                stealthParams.width = bounds.width
                stealthParams.height = bounds.height
                stealthParams.x = bounds.x
                stealthParams.y = bounds.y
                stealthView?.let {
                    try { windowManager?.updateViewLayout(it, stealthParams) } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        abortScrollSession()
        setStealthState(false)
        removeOverlays()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        abortScrollSession()
        setStealthState(false)
        removeOverlays()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun setCursorDragVisual(dragging: Boolean) {
        if (isCursorDragging != dragging) {
            isCursorDragging = dragging
            cursorView?.postInvalidate()
        }
    }

    private fun updateCursorLayoutPosition() {
        val view = cursorView ?: return
        val wm = windowManager ?: return
        if (!::cursorParams.isInitialized) return
        val newX = cursorX.roundToInt() - 18
        val newY = cursorY.roundToInt() - 4
        if (cursorParams.x != newX || cursorParams.y != newY) {
            cursorParams.x = newX
            cursorParams.y = newY
            try {
                wm.updateViewLayout(view, cursorParams)
            } catch (_: Exception) {}
        }
    }

    private fun abortScrollSession() {
        mainHandler.removeCallbacks(scrollHoldTimeoutRunnable)
        isScrollHolding = false
        isScrollStrokeActive = false
        activeScrollStroke = null
        pendingScrollDx = 0f
        pendingScrollDy = 0f
        setCursorDragVisual(false)
    }

    private fun setupOverlays() {
        mainHandler.post {
            if (cursorView != null) return@post

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            cursorX = screenWidth / 2f
            cursorY = screenHeight / 2f
            targetX = cursorX
            targetY = cursorY

            cursorView = object : View(this) {
                private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = 3.6f
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                }
                private val arrowPath = Path().apply {
                    moveTo(18f, 4f)
                    lineTo(18f, 44f)
                    lineTo(30f, 34f)
                    lineTo(40f, 52f)
                    lineTo(47f, 48f)
                    lineTo(37f, 30f)
                    lineTo(52f, 30f)
                    close()
                }
                private val fingerPath = Path().apply {
                    moveTo(18f, 4f)
                    lineTo(22f, 8f)
                    lineTo(22f, 22f)
                    lineTo(29f, 20f)
                    lineTo(29f, 24f)
                    lineTo(36f, 22f)
                    lineTo(36f, 26f)
                    lineTo(42f, 25f)
                    lineTo(42f, 38f)
                    lineTo(37f, 48f)
                    lineTo(15f, 48f)
                    lineTo(10f, 38f)
                    lineTo(4f, 29f)
                    lineTo(8f, 24f)
                    lineTo(14f, 30f)
                    lineTo(14f, 8f)
                    close()
                }

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val activePath = if (isCursorDragging) fingerPath else arrowPath
                    canvas.drawPath(activePath, fillPaint)
                    canvas.drawPath(activePath, strokePaint)
                }
            }.apply {
                visibility = View.GONE
            }

            cursorParams = WindowManager.LayoutParams(
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
                x = cursorX.roundToInt() - 18
                y = cursorY.roundToInt() - 4
            }

            stealthView = View(this).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                isFocusable = true
                setOnTouchListener { _, _ -> true }
                visibility = View.GONE
            }

            val stealthBounds = MouseEngine.computeStealthOverlayBounds(screenWidth, screenHeight)
            stealthParams = WindowManager.LayoutParams(
                stealthBounds.width,
                stealthBounds.height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = stealthBounds.x
                y = stealthBounds.y
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }

            try {
                wm.addView(cursorView, cursorParams)
                wm.addView(stealthView, stealthParams)
            } catch (_: Exception) {}

            updateOverlayVisibility()
        }
    }

    private fun removeOverlays() {
        mainHandler.removeCallbacks(watchTimeoutRunnable)
        mainHandler.removeCallbacks(scrollHoldTimeoutRunnable)
        mainHandler.post {
            isAnimatingCursor = false
            abortScrollSession()
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            cursorView?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            stealthView?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            cursorView = null
            stealthView = null
        }
    }

    private fun pokeDisplayBrightness(hideStealthAfterPoke: Boolean) {
        val view = stealthView ?: return
        val wm = windowManager ?: return
        try {
            val bounds = MouseEngine.computeStealthOverlayBounds(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels
            )
            stealthParams.width = bounds.width
            stealthParams.height = bounds.height
            stealthParams.x = bounds.x
            stealthParams.y = bounds.y
            wm.updateViewLayout(view, stealthParams)
        } catch (_: Exception) {}

        val pokePath = Path().apply { moveTo(1f, 1f); lineTo(1.5f, 1f) }
        val stroke = GestureDescription.StrokeDescription(pokePath, 0, 5)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val finishPoke = Runnable {
            try {
                if (hideStealthAfterPoke) {
                    view.visibility = View.GONE
                }
                wm.updateViewLayout(view, stealthParams)
            } catch (_: Exception) {}
        }

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                mainHandler.post(finishPoke)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                mainHandler.post(finishPoke)
            }
        }, null)

        if (!dispatched) {
            mainHandler.postDelayed(finishPoke, 25L)
        }
    }

    private fun setStealthState(active: Boolean) {
        mainHandler.post {
            if (isStealthActive == active && (!active || isBrightnessOverridden)) {
                updateOverlayVisibility()
                return@post
            }
            isStealthActive = active

            if (active) {
                abortScrollSession()
                cursorView?.visibility = View.GONE
                stealthView?.visibility = View.VISIBLE
                try {
                    if (!isBrightnessOverridden && Settings.System.canWrite(this)) {
                        savedBrightnessMode = Settings.System.getInt(
                            contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        )
                        savedBrightnessValue = Settings.System.getInt(
                            contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            128
                        )
                        Settings.System.putInt(
                            contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )
                        Settings.System.putInt(
                            contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            0
                        )
                        isBrightnessOverridden = true
                    }
                    if (::stealthParams.isInitialized) {
                        stealthParams.screenBrightness = 0.0f
                    }
                } catch (_: Exception) {}
                pokeDisplayBrightness(hideStealthAfterPoke = false)
            } else {
                try {
                    if (isBrightnessOverridden && Settings.System.canWrite(this)) {
                        Settings.System.putInt(
                            contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            savedBrightnessValue
                        )
                        Settings.System.putInt(
                            contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            savedBrightnessMode
                        )
                        isBrightnessOverridden = false
                    }
                    if (::stealthParams.isInitialized) {
                        stealthParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                } catch (_: Exception) {}
                updateOverlayVisibility()
                if (stealthView?.visibility == View.VISIBLE) {
                    pokeDisplayBrightness(hideStealthAfterPoke = true)
                } else {
                    stealthView?.visibility = View.GONE
                }
            }
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        isServiceMasterEnabled = enabled
        if (!enabled) {
            abortScrollSession()
            setStealthState(false)
        } else {
            WearableMouseClient.getInstance(applicationContext).ensureListening()
            updateOverlayVisibility()
        }
    }

    fun setDragScrollMode(enabled: Boolean, modeOverride: String? = null) {
        isDragScrollEnabled = enabled
        if (modeOverride != null) {
            watchControlMode = modeOverride
        }
        val prefs = getSharedPreferences("bandmouse_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("drag_scroll", enabled).apply()
        updateOverlayVisibility()
    }

    fun onWatchStateChanged(open: Boolean, modeOverride: String? = null) {
        mainHandler.post {
            mainHandler.removeCallbacks(watchTimeoutRunnable)
            if (modeOverride != null) {
                watchControlMode = modeOverride
            }
            isWatchAppOpen = open
            if (open) {
                mainHandler.postDelayed(watchTimeoutRunnable, 5000L)
                updateOverlayVisibility()
            } else {
                abortScrollSession()
                setStealthState(false)
            }
        }
    }

    fun onWatchHeartbeat() {
        mainHandler.post {
            mainHandler.removeCallbacks(watchTimeoutRunnable)
            if (!isWatchAppOpen) {
                isWatchAppOpen = true
                updateOverlayVisibility()
            }
            mainHandler.postDelayed(watchTimeoutRunnable, 5000L)
        }
    }

    fun toggleStealthMode() {
        if (!isServiceMasterEnabled) return
        onWatchHeartbeat()
        setStealthState(!isStealthActive)
    }

    private fun updateOverlayVisibility() {
        mainHandler.post {
            val isScrollOnlyMode = watchControlMode == "scroll"
            val showCursor = isServiceMasterEnabled && isWatchAppOpen && !isStealthActive && !isScrollOnlyMode
            cursorView?.visibility = if (showCursor) View.VISIBLE else View.GONE
            if (!isStealthActive) {
                stealthView?.visibility = View.GONE
            }
        }
    }

    fun moveCursor(dx: Int, dy: Int) {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            if (watchControlMode == "scroll") {
                watchControlMode = "cursor"
                updateOverlayVisibility()
            }
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.
