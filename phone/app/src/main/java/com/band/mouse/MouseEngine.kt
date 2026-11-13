package com.band.mouse

import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

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
