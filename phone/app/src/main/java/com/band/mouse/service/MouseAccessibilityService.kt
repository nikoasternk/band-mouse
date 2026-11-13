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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
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
        var cursorDpi: Int = MouseEngine.DEFAULT_DPI

        @Volatile
        var scrollDpi: Int = MouseEngine.DEFAULT_DPI

        @Volatile
        var watchTouchPpi: Float = MouseEngine.DEFAULT_WATCH_PPI

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

        @Volatile
        var isCustomPhoneBrightnessEnabled: Boolean = false

        @Volatile
        var customPhoneBrightnessPct: Int = 50
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
    private var containerOffsetX = 0f
    private var containerOffsetY = 0f
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
        isStealthActive = false
        syncPhoneBrightnessState()
        updateOverlayVisibility()
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

            applySpriteTranslation()

            if (abs(targetX - cursorX) < 0.15f && abs(targetY - cursorY) < 0.15f) {
                cursorX = targetX
                cursorY = targetY
                applySpriteTranslation()
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
        cursorDpi = prefs.getInt("cursor_dpi", MouseEngine.DEFAULT_DPI)
        scrollDpi = prefs.getInt("scroll_dpi", MouseEngine.DEFAULT_DPI)
        isServiceMasterEnabled = prefs.getBoolean("master_enabled", true)
        isDragScrollEnabled = prefs.getBoolean("drag_scroll", true)
        instance = this
        setupOverlays()
        WearableMouseClient.getInstance(applicationContext).ensureListening()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post {
            updatePhysicalScreenSize()
            targetX = targetX.coerceIn(0f, (screenWidth - 1).toFloat())
            targetY = targetY.coerceIn(0f, (screenHeight - 1).toFloat())
            cursorX = targetX
            cursorY = targetY
            filterX.reset(cursorX)
            filterY.reset(cursorY)
            applySpriteTranslation()

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
        isStealthActive = false
        isWatchAppOpen = false
        syncPhoneBrightnessState()
        removeOverlays()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        abortScrollSession()
        isStealthActive = false
        isWatchAppOpen = false
        syncPhoneBrightnessState()
        removeOverlays()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun updatePhysicalScreenSize() {
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            screenWidth = bounds.width().coerceAtLeast(1)
            screenHeight = bounds.height().coerceAtLeast(1)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels.coerceAtLeast(1)
            screenHeight = dm.heightPixels.coerceAtLeast(1)
        }
    }

    private fun applyCutoutMode(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun applySpriteTranslation() {
        val sprite = cursorSpriteView ?: return
        val container = cursorContainer
        if (container != null && container.isAttachedToWindow) {
            val loc = IntArray(2)
            container.getLocationOnScreen(loc)
            containerOffsetX = loc[0].toFloat()
            containerOffsetY = loc[1].toFloat()
        }
        sprite.translationX = cursorX - 18f - containerOffsetX
        sprite.translationY = cursorY - 4f - containerOffsetY
    }

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

            updatePhysicalScreenSize()
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
                    lineTo(20f, 5f)
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
                    lineTo(16f, 5f)
                    close()
                }

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val activePath = if (isCursorDragging) fingerPath else arrowPath
                    canvas.drawPath(activePath, fillPaint)
                    canvas.drawPath(activePath, strokePaint)
                }
            }

            cursorSpriteView = sprite

            cursorContainer = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                addView(sprite, FrameLayout.LayoutParams(64, 64))
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    containerOffsetX = loc[0].toFloat()
                    containerOffsetY = loc[1].toFloat()
                    applySpriteTranslation()
                }
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
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                applyCutoutMode(this)
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
                applyCutoutMode(this)
            }

            try {
                wm.addView(cursorContainer, cursorParams)
                wm.addView(stealthView, stealthParams)
            } catch (_: Exception) {}

            applySpriteTranslation()
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
            updatePhysicalScreenSize()
            val bounds = MouseEngine.computeStealthOverlayBounds(screenWidth, screenHeight)
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

    private fun syncPhoneBrightnessState() {
        val shouldStealth = isStealthActive
        val shouldCustom = !isStealthActive && isServiceMasterEnabled && isWatchAppOpen && isCustomPhoneBrightnessEnabled

        if (shouldStealth || shouldCustom) {
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
                    isBrightnessOverridden = true
                }
                if (Settings.System.canWrite(this)) {
                    Settings.System.putInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    val targetSysVal = if (shouldStealth) {
                        0
                    } else {
                        ((customPhoneBrightnessPct.coerceIn(0, 100) * 255) / 100).coerceIn(0, 255)
                    }
                    Settings.System.putInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        targetSysVal
                    )
                }
            } catch (_: Exception) {}

            if (::stealthParams.isInitialized) {
                stealthParams.screenBrightness = if (shouldStealth) 0.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            if (::cursorParams.isInitialized) {
                cursorParams.screenBrightness = if (shouldCustom) {
                    (customPhoneBrightnessPct.coerceIn(0, 100) / 100f).coerceAtLeast(0.005f)
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                cursorContainer?.let {
                    try { windowManager?.updateViewLayout(it, cursorParams) } catch (_: Exception) {}
                }
            }
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
            } catch (_: Exception) {}

            if (::stealthParams.isInitialized) {
                stealthParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            if (::cursorParams.isInitialized) {
                cursorParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                cursorContainer?.let {
                    try { windowManager?.updateViewLayout(it, cursorParams) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun setStealthState(active: Boolean) {
        mainHandler.post {
            if (isStealthActive == active) {
                syncPhoneBrightnessState()
                updateOverlayVisibility()
                return@post
            }
            isStealthActive = active

            if (active) {
                abortScrollSession()
                cursorContainer?.visibility = View.GONE
                stealthView?.visibility = View.VISIBLE
                syncPhoneBrightnessState()
                pokeDisplayBrightness(hideStealthAfterPoke = false)
            } else {
                syncPhoneBrightnessState()
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
            syncPhoneBrightnessState()
        } else {
            WearableMouseClient.getInstance(applicationContext).ensureListening()
            syncPhoneBrightnessState()
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

    fun setPhoneBrightnessFromWatch(enabled: Boolean, pct: Int) {
        mainHandler.post {
            isCustomPhoneBrightnessEnabled = enabled
            customPhoneBrightnessPct = pct.coerceIn(0, 100)
            syncPhoneBrightnessState()
            updateOverlayVisibility()
        }
    }

    fun onWatchStateChanged(
        open: Boolean,
        modeOverride: String? = null,
        dragOverride: Boolean? = null,
        brightOnOverride: Boolean? = null,
        brightPctOverride: Int? = null,
        watchPpiOverride: Float? = null
    ) {
        mainHandler.post {
            mainHandler.removeCallbacks(watchTimeoutRunnable)
            if (modeOverride != null) {
                watchControlMode = modeOverride
            }
            if (dragOverride != null) {
                isDragScrollEnabled = dragOverride
            }
            if (brightOnOverride != null) {
                isCustomPhoneBrightnessEnabled = brightOnOverride
            }
            if (brightPctOverride != null) {
                customPhoneBrightnessPct = brightPctOverride.coerceIn(0, 100)
            }
            if (watchPpiOverride != null && watchPpiOverride in 120.0f..640.0f) {
                watchTouchPpi = watchPpiOverride
            }
            isWatchAppOpen = open
            if (open) {
                mainHandler.postDelayed(watchTimeoutRunnable, 5000L)
                syncPhoneBrightnessState()
                updateOverlayVisibility()
            } else {
                abortScrollSession()
                setStealthState(false)
                syncPhoneBrightnessState()
            }
        }
    }

    fun onWatchHeartbeat() {
        mainHandler.post {
            mainHandler.removeCallbacks(watchTimeoutRunnable)
            if (!isWatchAppOpen) {
                isWatchAppOpen = true
                syncPhoneBrightnessState()
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
            val needContainerForOverlay = isServiceMasterEnabled && isWatchAppOpen && !isStealthActive
            val showSprite = needContainerForOverlay && !isScrollOnlyMode

            cursorContainer?.visibility = if (needContainerForOverlay) View.VISIBLE else View.GONE
            cursorSpriteView?.visibility = if (showSprite) View.VISIBLE else View.GONE
            if (showSprite) {
                applySpriteTranslation()
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
            updatePhysicalScreenSize()

            val nextTarget = MouseEngine.computeLinearCursorTarget(
                targetX,
                targetY,
                dx,
                dy,
                cursorDpi,
                screenWidth,
                screenHeight,
                watchTouchPpi
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
            applySpriteTranslation()
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

            updatePhysicalScreenSize()

            val useRealCursorDrag = isDragScrollEnabled && watchControlMode != "scroll"
            val scaled = if (useRealCursorDrag || watchControlMode == "scroll") {
                MouseEngine.computeScrollDelta(dx, dy, scrollDpi, watchTouchPpi)
            } else {
                MouseEngine.computeFourWayScrollDelta(dx, dy, scrollDpi, watchTouchPpi)
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

        updatePhysicalScreenSize()

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

        updatePhysicalScreenSize()

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
