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
import android.widget.FrameLayout
import com.band.mouse.MouseEngine
import com.band.mouse.OneEuroFilter
import com.band.mouse.transport.WearableMouseClient
import kotlin.math.abs
import kotlin.math.hypot

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
    private var cursorContainer: FrameLayout? = null
    private var cursorSpriteView: View? = null
    private var stealthView: View? = null
    private lateinit var cursorParams: WindowManager.LayoutParams
    private lateinit var stealthParams: WindowManager.LayoutParams

    private val filterX = OneEuroFilter(minCutoff = 3.2f, beta = 0.045f, dCutoff = 1.0f)
    private val filterY = OneEuroFilter(minCutoff = 3.2f, beta = 0.045f, dCutoff = 1.0f)

    private var cursorX = 500f
    private var cursorY = 1000f
    private var targetX = 500f
    private var targetY = 1000f
    private var lastFrameTimeNanos = 0L
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
            val sprite = cursorSpriteView
            if (sprite == null || !isServiceMasterEnabled || !isWatchAppOpen || isStealthActive) {
                isAnimatingCursor = false
                lastFrameTimeNanos = 0L
                return
            }

            val dtSec = if (lastFrameTimeNanos == 0L) {
                0.0083f
            } else {
                ((frameTimeNanos - lastFrameTimeNanos) * 1e-9f).coerceIn(0.004f, 0.033f)
            }
            lastFrameTimeNanos = frameTimeNanos

            val stepped = MouseEngine.stepCursorConstantRate(
                cursorX,
                cursorY,
                targetX,
                targetY,
                dtSec,
                filterX,
                filterY
            )
            cursorX = stepped.first
            cursorY = stepped.second

            sprite.translationX = cursorX - 18f
            sprite.translationY = cursorY - 4f

            if (abs(targetX - cursorX) < 0.15f && abs(targetY - cursorY) < 0.15f) {
                cursorX = targetX
                cursorY = targetY
                sprite.translationX = cursorX - 18f
                sprite.translationY = cursorY - 4f
                isAnimatingCursor = false
                lastFrameTimeNanos = 0L
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private val scrollGestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            onContinuousScrollSliceCompleted()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            activeScrollStroke = null
            isScrollStrokeActive = false
            if (isScrollHolding && hypot(pendingScrollDx, pendingScrollDy) >= 0.4f) {
                startContinuousScrollStroke()
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
            filterX.reset(cursorX)
            filterY.reset(cursorY)
            cursorSpriteView?.translationX = cursorX - 18f
            cursorSpriteView?.translationY = cursorY - 4f

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
            cursorSpriteView?.invalidate()
        }
    }

    private fun startCursorAnimationIfNeeded() {
        if (!isAnimatingCursor) {
            isAnimatingCursor = true
            lastFrameTimeNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
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
            if (cursorContainer != null) return@post

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            cursorX = screenWidth / 2f
            cursorY = screenHeight / 2f
            targetX = cursorX
            targetY = cursorY
            filterX.reset(cursorX)
            filterY.reset(cursorY)

            val sprite = object : View(this) {
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
                translationX = cursorX - 18f
                translationY = cursorY - 4f
            }

            cursorSpriteView = sprite

            cursorContainer = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                addView(sprite, FrameLayout.LayoutParams(64, 64))
                visibility = View.GONE
            }

            cursorParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
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
                wm.addView(cursorContainer, cursorParams)
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
            lastFrameTimeNanos = 0L
            abortScrollSession()
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            cursorContainer?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            stealthView?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            cursorSpriteView = null
            cursorContainer = null
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
                finishPoke.run()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                finishPoke.run()
            }
        }, mainHandler)

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
                cursorContainer?.visibility = View.GONE
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
            cursorContainer?.visibility = if (showCursor) View.VISIBLE else View.GONE
            if (showCursor) {
                cursorSpriteView?.translationX = cursorX - 18f
                cursorSpriteView?.translationY = cursorY - 4f
            }
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
            startCursorAnimationIfNeeded()
        }
    }

    fun performClick() {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            abortScrollSession()
            cursorX = targetX
            cursorY = targetY
            filterX.reset(cursorX)
            filterY.reset(cursorY)
            cursorSpriteView?.translationX = cursorX - 18f
            cursorSpriteView?.translationY = cursorY - 4f
            val path = Path().apply {
                moveTo(targetX, targetY)
                lineTo(targetX + 0.3f, targetY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 45)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, mainHandler)
        }
    }

    private fun applyIncomingScrollOptions(dragOverride: Boolean?, modeOverride: String?) {
        var visibilityChanged = false
        if (dragOverride != null && dragOverride != isDragScrollEnabled) {
            isDragScrollEnabled = dragOverride
            val prefs = getSharedPreferences("bandmouse_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("drag_scroll", dragOverride).apply()
        }
        if (modeOverride != null && modeOverride != watchControlMode) {
            watchControlMode = modeOverride
            visibilityChanged = true
        }
        if (visibilityChanged) {
            updateOverlayVisibility()
        }
    }

    fun performScrollStart(dragOverride: Boolean?, modeOverride: String?) {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            applyIncomingScrollOptions(dragOverride, modeOverride)
            mainHandler.removeCallbacks(scrollHoldTimeoutRunnable)
            mainHandler.postDelayed(scrollHoldTimeoutRunnable, 2500L)
            isScrollHolding = true
            setCursorDragVisual(true)
        }
    }

    fun performScrollHold(dragOverride: Boolean?, modeOverride: String?) {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            applyIncomingScrollOptions(dragOverride, modeOverride)
            mainHandler.removeCallbacks(scrollHoldTimeoutRunnable)
            mainHandler.postDelayed(scrollHoldTimeoutRunnable, 2500L)
            isScrollHolding = true
            setCursorDragVisual(true)
        }
    }

    fun performScrollMove(dx: Int, dy: Int, dragOverride: Boolean?, modeOverride: String?) {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            applyIncomingScrollOptions(dragOverride, modeOverride)
            mainHandler.removeCallbacks(scrollHoldTimeoutRunnable)
            mainHandler.postDelayed(scrollHoldTimeoutRunnable, 2500L)
            isScrollHolding = true
            setCursorDragVisual(true)

            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels

            val useRealCursorDrag = isDragScrollEnabled && watchControlMode != "scroll"
            val scaled = if (useRealCursorDrag || watchControlMode == "scroll") {
                MouseEngine.computeScrollDelta(dx, dy, scrollMultiplier)
            } else {
                MouseEngine.computeFourWayScrollDelta(dx, dy, scrollMultiplier)
            }

            if (useRealCursorDrag) {
                targetX = (targetX + scaled.first).coerceIn(4f, (screenWidth - 5).toFloat())
                targetY = (targetY + scaled.second).coerceIn(4f, (screenHeight - 5).toFloat())
                startCursorAnimationIfNeeded()
            }

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
            pendingScrollDx = 0f
            pendingScrollDy = 0f
            setCursorDragVisual(false)
        }
    }

    private fun startContinuousScrollStroke() {
        val dist = hypot(pendingScrollDx, pendingScrollDy)
        if (dist < 0.4f) {
            isScrollStrokeActive = false
            return
        }

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        val takeRatio = 0.68f
        val dx = pendingScrollDx * takeRatio
        val dy = pendingScrollDy * takeRatio
        pendingScrollDx -= dx
        pendingScrollDy -= dy

        val useRealCursorDrag = isDragScrollEnabled && watchControlMode != "scroll"

        if (useRealCursorDrag) {
            holdWobble = -holdWobble
            val startOriginX = cursorX.coerceIn(4f, (screenWidth - 5).toFloat())
            val startOriginY = cursorY.coerceIn(4f, (screenHeight - 5).toFloat())
            val dragStep = MouseEngine.computeRealCursorDragStep(
                startOriginX,
                startOriginY,
                dx,
                dy,
                holdWobble,
                screenWidth,
                screenHeight
            )
            scrollCurX = dragStep.endX
            scrollCurY = dragStep.endY

            val path = Path().apply {
                moveTo(dragStep.startX, dragStep.startY)
                lineTo(dragStep.endX, dragStep.endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 44, true)
            activeScrollStroke = stroke
            isScrollStrokeActive = true
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            if (!dispatchGesture(gesture, scrollGestureCallback, mainHandler)) {
                activeScrollStroke = null
                isScrollStrokeActive = false
            }
            return
        }

        val originX = if (watchControlMode == "scroll") screenWidth / 2f else targetX
        val originY = if (watchControlMode == "scroll") screenHeight / 2f else targetY
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val step = MouseEngine.computeInitialScrollStep(
            originX,
            originY,
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

        val stroke = GestureDescription.StrokeDescription(path, 0, 48, true)
        activeScrollStroke = stroke
        isScrollStrokeActive = true

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        if (!dispatchGesture(gesture, scrollGestureCallback, mainHandler)) {
            activeScrollStroke = null
            isScrollStrokeActive = false
        }
    }

    private fun onContinuousScrollSliceCompleted() {
        val prevStroke = activeScrollStroke
        if (prevStroke == null) {
            isScrollStrokeActive = false
            if (isScrollHolding && hypot(pendingScrollDx, pendingScrollDy) >= 0.4f) {
                startContinuousScrollStroke()
            }
            return
        }

        if (!isScrollHolding) {
            holdWobble = -holdWobble
            val stopPath = Path().apply {
                moveTo(scrollCurX, scrollCurY)
                lineTo(scrollCurX + holdWobble, scrollCurY)
            }
            activeScrollStroke = null
            pendingScrollDx = 0f
            pendingScrollDy = 0f
            try {
                val finalStroke = prevStroke.continueStroke(stopPath, 0, 14, false)
                val gesture = GestureDescription.Builder().addStroke(finalStroke).build()
                if (!dispatchGesture(gesture, scrollGestureCallback, mainHandler)) {
                    isScrollStrokeActive = false
                }
            } catch (_: Exception) {
                isScrollStrokeActive = false
            }
            return
        }

        val pendingMag = hypot(pendingScrollDx, pendingScrollDy)
        val isIdleSlice = pendingMag < 0.5f
        val takeRatio = if (isIdleSlice) 1.0f else 0.68f
        val stepX = if (isIdleSlice) 0f else pendingScrollDx * takeRatio
        val stepY = if (isIdleSlice) 0f else pendingScrollDy * takeRatio
        pendingScrollDx = if (isIdleSlice) 0f else (pendingScrollDx - stepX)
        pendingScrollDy = if (isIdleSlice) 0f else (pendingScrollDy - stepY)

        val sliceDuration = if (isIdleSlice) 20L else 44L

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        val useRealCursorDrag = isDragScrollEnabled && watchControlMode != "scroll"
        holdWobble = -holdWobble

        if (useRealCursorDrag) {
            val dragStep = MouseEngine.computeRealCursorDragStep(
                scrollCurX,
                scrollCurY,
                stepX,
                stepY,
                holdWobble,
                screenWidth,
                screenHeight
            )
            val path = Path().apply {
                moveTo(dragStep.startX, dragStep.startY)
                lineTo(dragStep.endX, dragStep.endY)
            }
            scrollCurX = dragStep.endX
            scrollCurY = dragStep.endY

            try {
                val nextStroke = prevStroke.continueStroke(path, 0, sliceDuration, true)
                activeScrollStroke = nextStroke
                val gesture = GestureDescription.Builder().addStroke(nextStroke).build()
                if (!dispatchGesture(gesture, scrollGestureCallback, mainHandler)) {
                    activeScrollStroke = null
                    isScrollStrokeActive = false
                }
            } catch (_: Exception) {
                activeScrollStroke = null
                isScrollStrokeActive = false
            }
            return
        }

        val contStep = MouseEngine.computeContinuationScrollStep(
            scrollCurX,
            scrollCurY,
            stepX,
            stepY,
            holdWobble,
            screenWidth,
            screenHeight
        )

        if (contStep.needsRecycle) {
            pendingScrollDx += stepX
            pendingScrollDy += stepY
            val stopPath = Path().apply {
                moveTo(scrollCurX, scrollCurY)
                lineTo(scrollCurX + holdWobble, scrollCurY)
            }
            activeScrollStroke = null
            try {
                val finalStroke = prevStroke.continueStroke(stopPath, 0, 14, false)
                val gesture = GestureDescription.Builder().addStroke(finalStroke).build()
                if (!dispatchGesture(gesture, scrollGestureCallback, mainHandler)) {
                    isScrollStrokeActive = false
                }
            } catch (_: Exception) {
                isScrollStrokeActive = false
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
            val nextStroke = prevStroke.continueStroke(path, 0, sliceDuration, true)
            activeScrollStroke = nextStroke
            val gesture = GestureDescription.Builder().addStroke(nextStroke).build()
            if (!dispatchGesture(gesture, scrollGestureCallback, mainHandler)) {
                activeScrollStroke = null
                isScrollStrokeActive = false
            }
        } catch (_: Exception) {
            activeScrollStroke = null
            isScrollStrokeActive = false
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
