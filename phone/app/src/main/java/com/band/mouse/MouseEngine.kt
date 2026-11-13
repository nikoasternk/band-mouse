package com.band.mouse

import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math. PI
import kotlin.math.abs
import kotlin.math.hypot

data class OverlayBounds(
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int
)

data class DragStepResult(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val needsRecycle: Boolean
)

class OneEuroFilter(
    private val minCutoff: Float = 3.2f,
    private val beta: Float = 0.045f,
    private val dCutoff: Float = 1.0f
) {
    private var xPrev = Float.NaN
    private var dxPrev = 0f

    fun reset(initialValue: Float) {
        xPrev = initialValue
        dxPrev = 0f
    }

    private fun smoothingFactor(dt: Float, cutoff: Float): Float {
        val r = (2.0 * PI * cutoff * dt).toFloat()
        return r / (r + 1.0f)
    }

    fun filter(value: Float, dtSeconds: Float): Float {
        val dt = dtSeconds.coerceIn(0.002f, 0.050f)
        if (xPrev.isNaN()) {
            xPrev = value
            dxPrev = 0f
            return value
        }
        val dValue = (value - xPrev) / dt
        val alphaD = smoothingFactor(dt, dCutoff)
        val edValue = alphaD * dValue + (1.0f - alphaD) * dxPrev
        dxPrev = edValue

        val cutoff = minCutoff + beta * abs(edValue)
        val alpha = smoothingFactor(dt, cutoff)
        val result = alpha * value + (1.0f - alpha) * xPrev
        xPrev = result
        return result
    }
}

sealed class WatchCommand {
    data class Move(val dx: Int, val dy: Int) : WatchCommand()
    data class ScrollStart(val dragCursor: Boolean?, val mode: String?) : WatchCommand()
    data class ScrollMove(val dx: Int, val dy: Int, val dragCursor: Boolean?, val mode: String?) : WatchCommand()
    data class ScrollHold(val dragCursor: Boolean?, val mode: String?) : WatchCommand()
    data object ScrollEnd : WatchCommand()
    data class ScrollModeConfig(val dragCursor: Boolean, val mode: String?) : WatchCommand()
    data object Tap : WatchCommand()
    data object Back : WatchCommand()
    data object Home : WatchCommand()
    data object Recents : WatchCommand()
    data object Stealth : WatchCommand()
    data object Ping : WatchCommand()
    data class State(val open: Boolean, val mode: String?) : WatchCommand()
    data class Handshake(val step: Int) : WatchCommand()
    data object Unknown : WatchCommand()
}

object MouseEngine {

    fun computeStealthOverlayBounds(screenWidth: Int, screenHeight: Int): OverlayBounds {
        val maxDim = maxOf(screenWidth, screenHeight) + 1600
        return OverlayBounds(
            width = maxDim,
            height = maxDim,
            x = -400,
            y = -400
        )
    }

    fun computeLinearCursorTarget(
        currentTargetX: Float,
        currentTargetY: Float,
        dx: Int,
        dy: Int,
        sensitivity: Float,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Float, Float> {
        val w = screenWidth.coerceAtLeast(1)
        val h = screenHeight.coerceAtLeast(1)
        val nx = (currentTargetX + dx * sensitivity).coerceIn(0f, (w - 1).toFloat())
        val ny = (currentTargetY + dy * sensitivity).coerceIn(0f, (h - 1).toFloat())
        return Pair(nx, ny)
    }

    fun stepCursorConstantRate(
        currentX: Float,
        currentY: Float,
        targetX: Float,
        targetY: Float,
        dtSeconds: Float,
        filterX: OneEuroFilter,
        filterY: OneEuroFilter
    ): Pair<Float, Float> {
        val dt = dtSeconds.coerceIn(0.004f, 0.033f)
        val diffX = targetX - currentX
        val diffY = targetY - currentY
        val dist = hypot(diffX, diffY)
        if (dist < 0.15f) {
            filterX.reset(targetX)
            filterY.reset(targetY)
            return Pair(targetX, targetY)
        }
        val packetWindowSec = 0.020f
        val linearAlpha = (dt / packetWindowSec).coerceIn(0.25f, 0.85f)
        val steppedX = currentX + diffX * linearAlpha
        val steppedY = currentY + diffY * linearAlpha
        val smoothX = filterX.filter(steppedX, dt)
        val smoothY = filterY.filter(steppedY, dt)
        return Pair(smoothX, smoothY)
    }

    fun computeScrollDelta(dx: Int, dy: Int, scrollMultiplier: Float): Pair<Float, Float> {
        val dragScale = scrollMultiplier * 0.55f
        return Pair(dx * dragScale, dy * dragScale)
    }

    fun computeFourWayScrollDelta(dx: Int, dy: Int, scrollMultiplier: Float): Pair<Float, Float> {
        val dragScale = scrollMultiplier * 0.55f
        return if (abs(dx) > abs(dy)) {
            Pair(dx * dragScale, 0f)
        } else {
            Pair(0f, dy * dragScale)
        }
    }

    fun computeRealCursorDragStep(
        curX: Float,
        curY: Float,
        stepX: Float,
        stepY: Float,
        wobbleOffset: Float,
        screenWidth: Int,
        screenHeight: Int
    ): DragStepResult {
        val w = screenWidth.coerceAtLeast(10)
        val h = screenHeight.coerceAtLeast(10)
        val clampedStartX = curX.coerceIn(4f, (w - 5).toFloat())
        val clampedStartY = curY.coerceIn(4f, (h - 5).toFloat())
        var nextX = (clampedStartX + stepX).coerceIn(4f, (w - 5).toFloat())
        var nextY = (clampedStartY + stepY).coerceIn(4f, (h - 5).toFloat())

        if (abs(nextX - clampedStartX) < 0.1f && abs(nextY - clampedStartY) < 0.1f) {
            val safeWobble = if (abs(wobbleOffset) < 0.1f) 0.4f else wobbleOffset
            nextX = (clampedStartX + safeWobble).coerceIn(4f, (w - 5).toFloat())
        }
        return DragStepResult(clampedStartX, clampedStartY, nextX, nextY, false)
    }

    fun computeInitialScrollStep(
        originX: Float,
        originY: Float,
        deltaX: Float,
        deltaY: Float,
        touchSlop: Float,
        screenWidth: Int,
        screenHeight: Int
    ): DragStepResult {
        val w = screenWidth.coerceAtLeast(200)
        val h = screenHeight.coerceAtLeast(200)
        val startX = originX.coerceIn(70f, (w - 70).toFloat())
        val startY = originY.coerceIn(90f, (h - 90).toFloat())
        val dist = hypot(deltaX, deltaY)
        if (dist < 0.4f) {
            return DragStepResult(startX, startY, startX + 0.5f, startY, false)
        }
        val ux = deltaX / dist
        val uy = deltaY / dist
        val slop = touchSlop.coerceIn(18f, 30f)
        val endX = (startX + ux * slop + deltaX).coerceIn(50f, (w - 50).toFloat())
        val endY = (startY + uy * slop + deltaY).coerceIn(70f, (h - 70).toFloat())
        return DragStepResult(startX, startY, endX, endY, false)
    }

    fun computeContinuationScrollStep(
        curX: Float,
        curY: Float,
        stepX: Float,
        stepY: Float,
        wobbleOffset: Float,
        screenWidth: Int,
        screenHeight: Int
    ): DragStepResult {
        val w = screenWidth.coerceAtLeast(200)
        val h = screenHeight.coerceAtLeast(200)
        val rawNextX = curX + stepX
        val rawNextY = curY + stepY
        val marginX = 55f
        val marginY = 75f
        val outOfBounds = rawNextX < marginX ||
                rawNextX > (w - marginX) ||
                rawNextY < marginY ||
                rawNextY > (h - marginY)

        if (outOfBounds && (abs(stepX) > 0.5f || abs(stepY) > 0.5f)) {
            return DragStepResult(curX, curY, curX + 0.4f, curY, true)
        }
        var clampedX = rawNextX.coerceIn(marginX, (w - marginX).coerceAtLeast(marginX + 1f))
        val clampedY = rawNextY.coerceIn(marginY, (h - marginY).coerceAtLeast(marginY + 1f))
        if (abs(clampedX - curX) < 0.1f && abs(clampedY - curY) < 0.1f) {
            val safeWobble = if (abs(wobbleOffset) < 0.1f) 0.4f else wobbleOffset
            clampedX = (curX + safeWobble).coerceIn(marginX, (w - marginX).coerceAtLeast(marginX + 1f))
        }
        return DragStepResult(curX, curY, clampedX, clampedY, false)
    }

    fun parseWatchPacket(rawPayload: String): WatchCommand {
        try {
            var text = rawPayload.trim()
            while (text.startsWith("\"") && text.endsWith("\"")) {
                text = JSONTokener(text).nextValue().toString()
            }
            val packet = JSONObject(text)
            val dragOpt = if (packet.has("drag")) packet.optBoolean("drag", true) else null
            val modeOpt = if (packet.has("mode")) packet.optString("mode", "")?.takeIf { it.isNotEmpty() } else null
            return when (packet.optString("tag")) {
                "mouse" -> {
                    when (packet.optString("act")) {
                        "m" -> WatchCommand.Move(packet.optInt("dx", 0), packet.optInt("dy", 0))
                        "s_start" -> WatchCommand.ScrollStart(dragOpt, modeOpt)
                        "s_move", "s" -> WatchCommand.ScrollMove(packet.optInt("dx", 0), packet.optInt("dy", 0), dragOpt, modeOpt)
                        "s_hold" -> WatchCommand.ScrollHold(dragOpt, modeOpt)
                        "s_end" -> WatchCommand.ScrollEnd
                        "s_mode" -> WatchCommand.ScrollModeConfig(packet.optBoolean("drag", true), modeOpt)
                        "tap" -> WatchCommand.Tap
                        "back" -> WatchCommand.Back
                        "home" -> WatchCommand.Home
                        "recents" -> WatchCommand.Recents
                        "stealth" -> WatchCommand.Stealth
                        "ping" -> WatchCommand.Ping
                        "state" -> WatchCommand.State(packet.optBoolean("open", false), modeOpt)
                        else -> WatchCommand.Unknown
                    }
                }
                "handshake" -> WatchCommand.Handshake(packet.optInt("step", 0))
                else -> WatchCommand.Unknown
            }
        } catch (_: Exception) {
            return WatchCommand.Unknown
        }
    }
}
