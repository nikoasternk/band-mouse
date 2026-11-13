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
    fun testLinearCursorHasZeroAccelerationAndClampsBounds() {
        val slowStep1 = MouseEngine.computeLinearCursorTarget(500f, 500f, 2, -3, 3.0f, 1080, 2400)
        val slowStep2 = MouseEngine.computeLinearCursorTarget(slowStep1.first, slowStep1.second, 2, -3, 3.0f, 1080, 2400)
        val fastSingleStep = MouseEngine.computeLinearCursorTarget(500f, 500f, 4, -6, 3.0f, 1080, 2400)
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
        println("[TEST-LOG] Stationary hold path length: $pathLen")
        assertTrue("Stationary hold path must be non-zero so Android never drops gesture", pathLen > 0.1f)
    }

    @Test
    fun testFourWayHybridScrollFromCursorPosition() {
        val vertDelta = MouseEngine.computeFourWayScrollDelta(3, -12, 8.0f)
        assertEquals(0f, vertDelta.first, 0.001f)
        assertNotEquals(0f, vertDelta.second, 0.001f)

        val horizDelta = MouseEngine.computeFourWayScrollDelta(15, 4, 8.0f)
        assertNotEquals(0f, horizDelta.first, 0.001f)
        assertEquals(0f, horizDelta.second, 0.001f)

        val initFromCursor = MouseEngine.computeInitialScrollStep(220f, 410f, vertDelta.first, vertDelta.second, 24f, 1080, 2400)
        assertEquals(220f, initFromCursor.startX, 0.01f)
        assertEquals(410f, initFromCursor.startY, 0.01f)
        assertEquals(220f, initFromCursor.endX, 0.01f)
        assertTrue(initFromCursor.endY < 410f)
    }

    @Test
    fun testWatchPacketProtocolParsingWithMode() {
        val scrollStartCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"s_start","drag":true,"mode":"hybrid"}""")
        assertEquals(WatchCommand.ScrollStart(true, "hybrid"), scrollStartCmd)

        val scrollMoveCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"s_move","dx":-5,"dy":12,"drag":false,"mode":"scroll"}""")
        assertEquals(WatchCommand.ScrollMove(-5, 12, false, "scroll"), scrollMoveCmd)

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
