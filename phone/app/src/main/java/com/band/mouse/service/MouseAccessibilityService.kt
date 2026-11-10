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
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.band.mouse.transport.WearableMouseClient
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

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

    private var pendingScrollDx = 0f
    private var pendingScrollDy = 0f
    private var isScrollStrokeActive = false

    private var screenWidth = 1080
    private var screenHeight = 2400

    private var savedBrightnessMode: Int = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    private var savedBrightnessValue: Int = 128
    private var isBrightnessOverridden: Boolean = false

    private val watchTimeoutRunnable = Runnable {
        isWatchAppOpen = false
        setStealthState(false)
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

            val newX = cursorX.roundToInt() + 2
            val newY = cursorY.roundToInt() + 2
            if (cursorParams.x != newX || cursorParams.y != newY) {
                cursorParams.x = newX
                cursorParams.y = newY
                try {
                    wm.updateViewLayout(view, cursorParams)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val prefs = getSharedPreferences("bandmouse_prefs", Context.MODE_PRIVATE)
        cursorSensitivity = prefs.getFloat("sensitivity", 3.0f)
        scrollMultiplier = prefs.getFloat("scroll_mult", 8.0f)
        isServiceMasterEnabled = prefs.getBoolean("master_enabled", true)
        instance = this
        setupOverlays()
        WearableMouseClient.getInstance(applicationContext).ensureListening()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        setStealthState(false)
        removeOverlays()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        setStealthState(false)
        removeOverlays()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

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

            stealthParams = WindowManager.LayoutParams(
                screenWidth + 400,
                screenHeight + 800,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = -200
                y = -400
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
        mainHandler.post {
            isAnimatingCursor = false
            isScrollStrokeActive = false
            pendingScrollDx = 0f
            pendingScrollDy = 0f
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
                isScrollStrokeActive = false
                pendingScrollDx = 0f
                pendingScrollDy = 0f
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
            setStealthState(false)
        } else {
            WearableMouseClient.getInstance(applicationContext).ensureListening()
            updateOverlayVisibility()
        }
    }

    fun onWatchStateChanged(open: Boolean) {
        mainHandler.post {
            mainHandler.removeCallbacks(watchTimeoutRunnable)
            isWatchAppOpen = open
            if (open) {
                mainHandler.postDelayed(watchTimeoutRunnable, 5000L)
                updateOverlayVisibility()
            } else {
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

            val mult = cursorSensitivity
            targetX = (targetX + dx * mult).coerceIn(0f, (screenWidth - 1).toFloat())
            targetY = (targetY + dy * mult).coerceIn(0f, (screenHeight - 1).toFloat())

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

    fun performScroll(dx: Int, dy: Int) {
        if (!isServiceMasterEnabled || isStealthActive) return
        onWatchHeartbeat()
        mainHandler.post {
            if (pendingScrollDy != 0f && dy != 0 && sign(pendingScrollDy) != sign(dy.toFloat()) && abs(dy) <= 2) {
                return@post
            }
            if (pendingScrollDx != 0f && dx != 0 && sign(pendingScrollDx) != sign(dx.toFloat()) && abs(dx) <= 2) {
                return@post
            }
            pendingScrollDx += dx
            pendingScrollDy += dy
            if (!isScrollStrokeActive) {
                dispatchNextScrollStroke()
            }
        }
    }

    private fun dispatchNextScrollStroke() {
        if (abs(pendingScrollDx) < 1f && abs(pendingScrollDy) < 1f) {
            pendingScrollDx = 0f
            pendingScrollDy = 0f
            isScrollStrokeActive = false
            return
        }

        val rawDx = pendingScrollDx
        val rawDy = pendingScrollDy
        pendingScrollDx = 0f
        pendingScrollDy = 0f

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat().coerceIn(18f, 28f)

        var moveX = 0f
        var moveY = 0f

        if (abs(rawDy) >= abs(rawDx)) {
            moveY = sign(rawDy) * touchSlop + rawDy * scrollMultiplier
        } else {
            moveX = sign(rawDx) * touchSlop + rawDx * scrollMultiplier
        }

        val startX = targetX.coerceIn(screenWidth * 0.2f, screenWidth * 0.8f)
        val startY = targetY.coerceIn(screenHeight * 0.28f, screenHeight * 0.72f)

        val endX = (startX + moveX).coerceIn(50f, (screenWidth - 50).toFloat())
        val endY = (startY + moveY).coerceIn(100f, (screenHeight - 100).toFloat())

        if (abs(endX - startX) < touchSlop + 4f && abs(endY - startY) < touchSlop + 4f) {
            isScrollStrokeActive = false
            return
        }

        isScrollStrokeActive = true
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 85)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                mainHandler.post {
                    isScrollStrokeActive = false
                    if (abs(pendingScrollDx) >= 1f || abs(pendingScrollDy) >= 1f) {
                        dispatchNextScrollStroke()
                    }
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                mainHandler.post {
                    isScrollStrokeActive = false
                    pendingScrollDx = 0f
                    pendingScrollDy = 0f
                }
            }
        }, null)

        if (!dispatched) {
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
