package com.band.mouse

import org.json.JSONObject
import org.json.JSONTokener
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

sealed class WatchCommand {
    data class Move(val dx: Int, val dy: Int) : WatchCommand()
    data class ScrollMove(val dx: Int, val dy: Int) : WatchCommand()
    data object ScrollEnd : WatchCommand()
    data object Tap : WatchCommand()
    data object Back : WatchCommand()
    data object Home : WatchCommand()
    data object Recents : WatchCommand()
    data object Stealth : WatchCommand()
    data object Ping : WatchCommand()
    data class State(val open: Boolean) : WatchCommand()
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

    fun computeInitialScrollStep(
        centerX: Float,
        centerY: Float,
        deltaX: Float,
        deltaY: Float,
        touchSlop: Float,
        screenWidth: Int,
        screenHeight: Int
    ): DragStepResult {
        val dist = hypot(deltaX, deltaY)
        if (dist < 0.5f) {
            return DragStepResult(centerX, centerY, centerX, centerY, false)
        }
        val ux = deltaX / dist
        val uy = deltaY / dist
        val slop = touchSlop.coerceIn(18f, 34f)
        val endX = (centerX + ux * slop + deltaX).coerceIn(60f, (screenWidth - 60).toFloat())
        val endY = (centerY + uy * slop + deltaY).coerceIn(80f, (screenHeight - 80).toFloat())
        return DragStepResult(centerX, centerY, endX, endY, false)
    }

    fun computeContinuationScrollStep(
        curX: Float,
        curY: Float,
        stepX: Float,
        stepY: Float,
        screenWidth: Int,
        screenHeight: Int
    ): DragStepResult {
        val rawNextX = curX + stepX
        val rawNextY = curY + stepY
        val marginX = 70f
        val marginY = 90f
        val outOfBounds = rawNextX < marginX ||
                rawNextX > (screenWidth - marginX) ||
                rawNextY < marginY ||
                rawNextY > (screenHeight - marginY)

        if (outOfBounds && (abs(stepX) > 0.5f || abs(stepY) > 0.5f)) {
            return DragStepResult(curX, curY, curX, curY, true)
        }
        val clampedX = rawNextX.coerceIn(marginX, (screenWidth - marginX).coerceAtLeast(marginX + 1f))
        val clampedY = rawNextY.coerceIn(marginY, (screenHeight - marginY).coerceAtLeast(marginY + 1f))
        return DragStepResult(curX, curY, clampedX, clampedY, false)
    }

    fun parseWatchPacket(rawPayload: String): WatchCommand {
        try {
            var text = rawPayload.trim()
            while (text.startsWith("\"") && text.endsWith("\"")) {
                text = JSONTokener(text).nextValue().toString()
            }
            val packet = JSONObject(text)
            return when (packet.optString("tag")) {
                "mouse" -> {
                    when (packet.optString("act")) {
                        "m" -> WatchCommand.Move(packet.optInt("dx", 0), packet.optInt("dy", 0))
                        "s_move", "s" -> WatchCommand.ScrollMove(packet.optInt("dx", 0), packet.optInt("dy", 0))
                        "s_end" -> WatchCommand.ScrollEnd
                        "tap" -> WatchCommand.Tap
                        "back" -> WatchCommand.Back
                        "home" -> WatchCommand.Home
                        "recents" -> WatchCommand.Recents
                        "stealth" -> WatchCommand.Stealth
                        "ping" -> WatchCommand.Ping
                        "state" -> WatchCommand.State(packet.optBoolean("open", false))
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
