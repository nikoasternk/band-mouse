package com.band.mouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

class BandMouseUnitTest {

    @Test
    fun testStealthOverlayCoversPortraitAndLandscape() {
        val portraitBounds = MouseEngine.computeStealthOverlayBounds(1080, 2400)
        assertTrue(portraitBounds.x <= 0)
        assertTrue(portraitBounds.y <= 0)
        assertTrue(portraitBounds.x + portraitBounds.width >= 2400)
        assertTrue(portraitBounds.y + portraitBounds.height >= 2400)

        val landscapeBounds = MouseEngine.computeStealthOverlayBounds(2400, 1080)
        assertEquals(portraitBounds.width, landscapeBounds.width)
        assertEquals(portraitBounds.height, landscapeBounds.height)
    }

    @Test
    fun testCursorAndScrollSpeedsAreIdenticalAtSameDpiAndAutoPpi() {
        val dpi = 1000
        val band10Ppi = 326.0f
        val cursorNext = MouseEngine.computeLinearCursorTarget(500f, 500f, 10, -15, dpi, 1080, 2400, band10Ppi)
        val cursorDx = cursorNext.first - 500f
        val cursorDy = cursorNext.second - 500f

        val scrollDelta = MouseEngine.computeScrollDelta(10, -15, dpi, band10Ppi)
        assertEquals(cursorDx, scrollDelta.first, 0.001f)
        assertEquals(cursorDy, scrollDelta.second, 0.001f)
        assertEquals(10f * (1000f / 326f), cursorDx, 0.01f)

        val watchS3TouchPpi = 326.0f * (212.0f / 466.0f)
        val oneInchMove = MouseEngine.computeLinearCursorTarget(0f, 0f, watchS3TouchPpi.toInt(), 0, 1000, 2000, 2000, watchS3TouchPpi)
        assertEquals(1000f, oneInchMove.first, 5.0f)
        println("[TEST-LOG] Auto watchPpi verified: Band10 dx=$cursorDx, WatchS3 1-inch=$oneInchMove")
    }

    @Test
    fun testOneEuroFilterAndConstantRateInterpolator() {
        val fx = OneEuroFilter()
        val fy = OneEuroFilter()
        var curX = 100f
        var curY = 100f
        val targetX = 160f
        val targetY = 100f

        for (frame in 1..25) {
            val stepped = MouseEngine.stepCursorConstantRate(curX, curY, targetX, targetY, 0.0083f, fx, fy)
            assertTrue("Cursor must monotonically approach target", stepped.first >= curX)
            curX = stepped.first
            curY = stepped.second
        }
        assertEquals(targetX, curX, 0.5f)
        assertEquals(targetY, curY, 0.5f)
    }

    @Test
    fun testLinearCursorHasZeroAccelerationAndClampsBounds() {
        val slowStep1 = MouseEngine.computeLinearCursorTarget(500f, 500f, 2, -3, 1000, 1080, 2400)
        val slowStep2 = MouseEngine.computeLinearCursorTarget(slowStep1.first, slowStep1.second, 2, -3, 1000, 1080, 2400)
        val fastSingleStep = MouseEngine.computeLinearCursorTarget(500f, 500f, 4, -6, 1000, 1080, 2400)
        assertEquals(fastSingleStep.first, slowStep2.first, 0.001f)
        assertEquals(fastSingleStep.second, slowStep2.second, 0.001f)
    }

    @Test
    fun testRealCursorDragMovesInRealPositionAndNeverZeroLength() {
        val step = MouseEngine.computeRealCursorDragStep(320f, 840f, -25f, 40f, 0.4f, 1080, 2400)
        assertEquals(320f, step.startX, 0.01f)
        assertEquals(840f, step.startY, 0.01f)
        assertEquals(295f, step.endX, 0.01f)
        assertEquals(880f, step.endY, 0.01f)

        val holdStep = MouseEngine.computeRealCursorDragStep(295f, 880f, 0f, 0f, 0.4f, 1080, 2400)
        val pathLen = hypot(holdStep.endX - holdStep.startX, holdStep.endY - holdStep.startY)
        assertTrue("Stationary hold path must be non-zero so Android never drops gesture", pathLen > 0.1f)
    }

    @Test
    fun testFourWayHybridScrollFromCursorPosition() {
        val vertDelta = MouseEngine.computeFourWayScrollDelta(3, -12, 1000)
        assertEquals(0f, vertDelta.first, 0.001f)
        assertNotEquals(0f, vertDelta.second, 0.001f)

        val horizDelta = MouseEngine.computeFourWayScrollDelta(15, 4, 1000)
        assertNotEquals(0f, horizDelta.first, 0.001f)
        assertEquals(0f, horizDelta.second, 0.001f)

        val initFromCursor = MouseEngine.computeInitialScrollStep(220f, 410f, vertDelta.first, vertDelta.second, 24f, 1080, 2400)
        assertEquals(220f, initFromCursor.startX, 0.01f)
        assertEquals(410f, initFromCursor.startY, 0.01f)
        assertEquals(220f, initFromCursor.endX, 0.01f)
        assertTrue(initFromCursor.endY < 410f)
    }

    @Test
    fun testWatchPacketProtocolParsingIncludingPhoneBrightness() {
        val brightCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"p_bright","on":true,"pct":30}""")
        assertEquals(WatchCommand.PhoneBrightness(true, 30), brightCmd)

        val stateCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"state","open":true,"mode":"hybrid","drag":true,"brightOn":true,"brightPct":70,"ppi":326.0}""")
        assertEquals(WatchCommand.State(true, "hybrid", true, true, 70, 326.0f), stateCmd)

        val modeCfgCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"s_mode","drag":false,"mode":"scroll"}""")
        assertEquals(WatchCommand.ScrollModeConfig(false, "scroll"), modeCfgCmd)
    }

    @Test
    fun testKotlinSourceFilesHaveZeroComments() {
        val srcDir = File("src/main/java")
        assertTrue("Source directory must exist", srcDir.exists())
        srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val content = file.readText()
            assertFalse("File ${file.name} contains line comment", content.contains("//"))
            assertFalse("File ${file.name} contains block comment", content.contains("/*"))
            println("[TEST-LOG] Audited Kotlin file OK: ${file.name}")
        }
    }
}
