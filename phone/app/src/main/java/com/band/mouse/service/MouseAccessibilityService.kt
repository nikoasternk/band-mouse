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

    private var screenWidth = 1080
    private var screenHeight = 2400

    private var savedBrightnessMode: Int = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    private var savedBrightnessValue: Int = 128
    private var isBrightnessOverridden: Boolean = false

    private val watchTimeoutRunnable = Runnable {
        isWatchAppOpen = false
        resetScrollState()
        setStealthState(false)
    }

    private val scrollHoldTimeoutRunnable = Runnable {
        isScrollHolding = false
        setCursorDragVisual(false)
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
                resetScrollState()
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
        resetScrollState()
        setStealthState(false)
        removeOverlays()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        resetScrollState()
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
            cursorView?.invalidate()
            updateCursorLayoutPosition()
        }
    }

    private fun updateCursorLayoutPosition() {
        val view = cursorView ?: return
        val wm = windowManager ?: return
        if (!::cursorParams.isInitialized) return
        val offset = if (isCursorDragging) -24 else 2
        val newX = cursorX.roundToInt() + offset
        val newY = cursorY.roundToInt() + offset
        if (cursorParams.x != newX || cursorParams.y != newY) {
            cursorParams.x = newX
            cursorParams.y = newY
            try {
                wm.updateViewLayout(view, cursorParams)
            } catch (_: Exception) {}
        }
    }

    private fun resetScrollState() {
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
                    strokeWidth = 4f
                    strokeJoin = Paint.Join.ROUND
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
                private val dragPath = Path().apply {
                    moveTo(24f, 2f)
                    lineTo(33f, 12f)
                    lineTo(27f, 12f)
                    lineTo(27f, 21f)
                    lineTo(36f, 21f)
                    lineTo(36f, 15f)
                    lineTo(46f, 24f)
                    lineTo(36f, 33f)
                    lineTo(36f, 27f)
                    lineTo(27f, 27f)
                    lineTo(27f, 36f)
                    lineTo(33f, 36f)
                    lineTo(24f, 46f)
                    lineTo(15f, 36f)
                    lineTo(21f, 36f)
                    lineTo(21f, 27f)
                    lineTo(12f, 27f)
                    lineTo(12f, 33f)
                    lineTo(2f, 24f)
                    lineTo(12f, 15f)
                    lineTo(12f, 21f)
                    lineTo(21f, 21f)
                    lineTo(21f, 12f)
                    lineTo(15f, 12f)
                    close()
                }

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val activePath = if (isCursorDragging) dragPath else arrowPath
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
                x = cursorX.roundToInt() + 2
                y = cursorY.roundToInt() + 2
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
            resetScrollState()
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

        val pokePath = Path().apply { moveTo(1f, 1f) }
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
                resetScrollState()
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
                val showCursor = isServiceMasterEnabled && isWatchAppOpen
                cursorView?.visibility = if (showCursor) View.VISIBLE else View.GONE
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
            resetScrollState()
            setStealthState(false)
        } else {
            WearableMouseClient.getInstance(applicationContext).ensureListening()
            updateOverlayVisibility()
        }
    }

    fun setDragScrollMode(enabled: Boolean) {
        isDragScrollEnabled = enabled
        val prefs = getSharedPreferences("bandmouse_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("drag_scroll", enabled).apply()
    }

    fun onWatchStateChanged(open: Boolean) {
        mainHandler.post {
            mainHandler.removeCallbacks(watchTimeoutRunnable)
            isWatchAppOpen = open
            if (open) {
                mainHandler.postDelayed(watchTimeoutRunnable, 5000L)
                updateOverlayVisibility()
            } else {
                resetScrollState()
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
            val showCursor = isServiceMasterEnabled && isWatchAppOpen && !isStealthActive
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
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels

            val nextTarget = MouseEngine.computeLinearCursorTarget(
                targetX,
                targetY,
                dx,
                dy,
                cursorSensitivity,
                screenWidth,
                screenHeight
            )
            targetX = nextTarget.first
            targetY = nextTarget.second

            if (!isAnimatingCursor) {
                isAnimatingCursor = true
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }
    }

    fun performClick() {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            resetScrollState()
            cursorX = targetX
            cursorY = targetY
            val path = Path().apply {
                moveTo(targetX, targetY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 45)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }
    }

    fun performScrollStart(dragOverride: Boolean?) {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            if (dragOverride != null && dragOverride != isDragScrollEnabled) {
                setDragScrollMode(dragOverride)
            }
            mainHandler.removeCallbacks(scrollHoldTimeoutRunnable)
            mainHandler.postDelayed(scrollHoldTimeoutRunnable, 1200L)
            isScrollHolding = true
            setCursorDragVisual(true)

            if (isDragScrollEnabled && !isScrollStrokeActive) {
                startContinuousScrollStroke()
            }
        }
    }

    fun performScrollHold(dragOverride: Boolean?) {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            if (dragOverride != null && dragOverride != isDragScrollEnabled) {
                setDragScrollMode(dragOverride)
            }
            mainHandler.removeCallbacks(scrollHoldTimeoutRunnable)
            mainHandler.postDelayed(scrollHoldTimeoutRunnable, 1200L)
            isScrollHolding = true
            setCursorDragVisual(true)
        }
    }

    fun performScrollMove(dx: Int, dy: Int, dragOverride: Boolean?) {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            if (dragOverride != null && dragOverride != isDragScrollEnabled) {
                setDragScrollMode(dragOverride)
            }
            mainHandler.removeCallbacks(scrollHoldTimeoutRunnable)
            mainHandler.postDelayed(scrollHoldTimeoutRunnable, 1200L)
            isScrollHolding = true
            setCursorDragVisual(true)

            val scaled = MouseEngine.computeScrollDelta(dx, dy, scrollMultiplier)
            pendingScrollDx += scaled.first
            pendingScrollDy += scaled.second

            if (!isScrollStrokeActive) {
                startContinuousScrollStroke()
            }
        }
    }

    fun performScrollEnd() {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            mainHandler.removeCallbacks(scrollHoldTimeoutRunnable)
            isScrollHolding = false
            setCursorDragVisual(false)
        }
    }

    private fun startContinuousScrollStroke() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        val dx = pendingScrollDx
        val dy = pendingScrollDy
        pendingScrollDx = 0f
        pendingScrollDy = 0f

        if (isDragScrollEnabled) {
            val dragStep = MouseEngine.computeRealCursorDragStep(
                targetX,
                targetY,
                dx,
                dy,
                screenWidth,
                screenHeight
            )
            scrollCurX = dragStep.endX
            scrollCurY = dragStep.endY
            targetX = scrollCurX
            targetY = scrollCurY
            cursorX = scrollCurX
            cursorY = scrollCurY
            updateCursorLayoutPosition()

            val path = Path().apply {
                moveTo(dragStep.startX, dragStep.startY)
                lineTo(dragStep.endX, dragStep.endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 40, true)
            activeScrollStroke = stroke
            isScrollStrokeActive = true
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            if (!dispatchGesture(gesture, scrollGestureCallback, null)) {
                resetScrollState()
            }
            return
        }

        val dist = hypot(dx, dy)
        if (dist < 0.8f) {
            isScrollStrokeActive = false
            return
        }

        val startX = screenWidth / 2f
        val startY = screenHeight / 2f
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val step = MouseEngine.computeInitialScrollStep(
            startX,
            startY,
            dx,
            dy,
            touchSlop,
            screenWidth,
            screenHeight
        )

        scrollCurX = step.endX
        scrollCurY = step.endY

        val path = Path().apply {
            moveTo(step.startX, step.startY)
            lineTo(step.endX, step.endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 42, true)
        activeScrollStroke = stroke
        isScrollStrokeActive = true

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        if (!dispatchGesture(gesture, scrollGestureCallback, null)) {
            resetScrollState()
        }
    }

    private fun onContinuousScrollSliceCompleted() {
        val prevStroke = activeScrollStroke
        if (prevStroke == null) {
            isScrollStrokeActive = false
            if (isScrollHolding && (isDragScrollEnabled || hypot(pendingScrollDx, pendingScrollDy) >= 1f)) {
                startContinuousScrollStroke()
            } else if (!isScrollHolding) {
                setCursorDragVisual(false)
            }
            return
        }

        if (!isScrollHolding) {
            val stopPath = Path().apply {
                moveTo(scrollCurX, scrollCurY)
                lineTo(scrollCurX, scrollCurY)
            }
            activeScrollStroke = null
            pendingScrollDx = 0f
            pendingScrollDy = 0f
            setCursorDragVisual(false)
            try {
                val finalStroke = prevStroke.continueStroke(stopPath, 0, 16, false)
                val gesture = GestureDescription.Builder().addStroke(finalStroke).build()
                if (!dispatchGesture(gesture, scrollGestureCallback, null)) {
                    resetScrollState()
                }
            } catch (_: Exception) {
                resetScrollState()
            }
            return
        }

        val stepX = pendingScrollDx
        val stepY = pendingScrollDy
        pendingScrollDx = 0f
        pendingScrollDy = 0f

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        if (isDragScrollEnabled) {
            val dragStep = MouseEngine.computeRealCursorDragStep(
                scrollCurX,
                scrollCurY,
                stepX,
                stepY,
                screenWidth,
                screenHeight
            )
            val path = Path().apply {
                moveTo(dragStep.startX, dragStep.startY)
                lineTo(dragStep.endX, dragStep.endY)
            }
            scrollCurX = dragStep.endX
            scrollCurY = dragStep.endY
            targetX = scrollCurX
            targetY = scrollCurY
            cursorX = scrollCurX
            cursorY = scrollCurY
            updateCursorLayoutPosition()

            try {
                val nextStroke = prevStroke.continueStroke(path, 0, 40, true)
                activeScrollStroke = nextStroke
                val gesture = GestureDescription.Builder().addStroke(nextStroke).build()
                if (!dispatchGesture(gesture, scrollGestureCallback, null)) {
                    resetScrollState()
                }
            } catch (_: Exception) {
                resetScrollState()
            }
            return
        }

        val contStep = MouseEngine.computeContinuationScrollStep(
            scrollCurX,
            scrollCurY,
            stepX,
            stepY,
            screenWidth,
            screenHeight
        )

        if (contStep.needsRecycle) {
            pendingScrollDx = stepX
            pendingScrollDy = stepY
            val stopPath = Path().apply {
                moveTo(scrollCurX, scrollCurY)
                lineTo(scrollCurX, scrollCurY)
            }
            activeScrollStroke = null
            try {
                val finalStroke = prevStroke.continueStroke(stopPath, 0, 14, false)
                val gesture = GestureDescription.Builder().addStroke(finalStroke).build()
                if (!dispatchGesture(gesture, scrollGestureCallback, null)) {
                    resetScrollState()
                }
            } catch (_: Exception) {
                resetScrollState()
            }
            return
        }

        val path = Path().apply {
            moveTo(contStep.startX, contStep.startY)
            lineTo(contStep.endX, contStep.endY)
        }
        scrollCurX = contStep.endX
        scrollCurY = contStep.endY

        try {
            val nextStroke = prevStroke.continueStroke(path, 0, 42, true)
            activeScrollStroke = nextStroke
            val gesture = GestureDescription.Builder().addStroke(nextStroke).build()
            if (!dispatchGesture(gesture, scrollGestureCallback, null)) {
                resetScrollState()
            }
        } catch (_: Exception) {
            resetScrollState()
        }
    }

    fun performBack() {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    fun performHome() {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    fun performRecents() {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }
}
