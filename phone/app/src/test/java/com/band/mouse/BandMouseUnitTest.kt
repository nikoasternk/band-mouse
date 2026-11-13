package com.band.mouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BandMouseUnitTest {

    @Test
    fun testStealthOverlayCoversPortraitAndLandscape() {
        val portraitBounds = MouseEngine.computeStealthOverlayBounds(1080, 2400)
        println("[TEST-LOG] Portrait stealth bounds: $portraitBounds")
        assertTrue(portraitBounds.x <= 0)
        assertTrue(portraitBounds.y <= 0)
        assertTrue(portraitBounds.x + portraitBounds.width >= 2400)
        assertTrue(portraitBounds.y + portraitBounds.height >= 2400)

        val landscapeBounds = MouseEngine.computeStealthOverlayBounds(2400, 1080)
        println("[TEST-LOG] Landscape stealth bounds: $landscapeBounds")
        assertTrue(landscapeBounds.x <= 0)
        assertTrue(landscapeBounds.y <= 0)
        assertTrue(landscapeBounds.x + landscapeBounds.width >= 2400)
        assertTrue(landscapeBounds.y + landscapeBounds.height >= 2400)
        assertEquals(portraitBounds.width, landscapeBounds.width)
        assertEquals(portraitBounds.height, landscapeBounds.height)
    }

    @Test
    fun testLinearCursorHasZeroAccelerationAndClampsBounds() {
        val slowStep1 = MouseEngine.computeLinearCursorTarget(500f, 500f, 2, -3, 3.0f, 1080, 2400)
        val slowStep2 = MouseEngine.computeLinearCursorTarget(slowStep1.first, slowStep1.second, 2, -3, 3.0f, 1080, 2400)
        val fastSingleStep = MouseEngine.computeLinearCursorTarget(500f, 500f, 4, -6, 3.0f, 1080, 2400)
        println("[TEST-LOG] Cursor 2x slow=${slowStep2} vs 1x fast=${fastSingleStep}")
        assertEquals(fastSingleStep.first, slowStep2.first, 0.001f)
        assertEquals(fastSingleStep.second, slowStep2.second, 0.001f)

        val clampedLandscape = MouseEngine.computeLinearCursorTarget(2390f, 1070f, 50, 50, 3.0f, 2400, 1080)
        assertEquals(2399f, clampedLandscape.first, 0.001f)
        assertEquals(1079f, clampedLandscape.second, 0.001f)
    }

    @Test
    fun testRealCursorDragMovesInRealPositionAnd360Degrees() {
        val step = MouseEngine.computeRealCursorDragStep(320f, 840f, -25f, 40f, 1080, 2400)
        println("[TEST-LOG] Real cursor drag step: $step")
        assertEquals(320f, step.startX, 0.01f)
        assertEquals(840f, step.startY, 0.01f)
        assertEquals(295f, step.endX, 0.01f)
        assertEquals(880f, step.endY, 0.01f)
        assertFalse(step.needsRecycle)

        val holdStep = MouseEngine.computeRealCursorDragStep(295f, 880f, 0f, 0f, 1080, 2400)
        assertEquals(295f, holdStep.endX, 0.01f)
        assertEquals(880f, holdStep.endY, 0.01f)
    }

    @Test
    fun test360DegreeContinuousScrollVectors() {
        val delta = MouseEngine.computeScrollDelta(10, -15, 8.0f)
        println("[TEST-LOG] 360 scroll delta for (10, -15) at 8x: $delta")
        assertTrue(delta.first > 0f)
        assertTrue(delta.second < 0f)

        val initStep = MouseEngine.computeInitialScrollStep(540f, 1200f, delta.first, delta.second, 24f, 1080, 2400)
        assertTrue(initStep.endX > 540f)
        assertTrue(initStep.endY < 1200f)
        assertFalse(initStep.needsRecycle)
    }

    @Test
    fun testWatchPacketProtocolParsing() {
        val moveCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"m","dx":7,"dy":-4}""")
        assertEquals(WatchCommand.Move(7, -4), moveCmd)

        val scrollStartCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"s_start","drag":true}""")
        assertEquals(WatchCommand.ScrollStart(true), scrollStartCmd)

        val scrollMoveCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"s_move","dx":-5,"dy":12,"drag":true}""")
        assertEquals(WatchCommand.ScrollMove(-5, 12, true), scrollMoveCmd)

        val scrollHoldCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"s_hold","drag":true}""")
        assertEquals(WatchCommand.ScrollHold(true), scrollHoldCmd)

        val scrollEndCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"s_end"}""")
        assertEquals(WatchCommand.ScrollEnd, scrollEndCmd)

        val modeCfgCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"s_mode","drag":false}""")
        assertEquals(WatchCommand.ScrollModeConfig(false), modeCfgCmd)

        val quotedStealth = MouseEngine.parseWatchPacket("\"{\\\"tag\\\":\\\"mouse\\\",\\\"act\\\":\\\"stealth\\\"}\"")
        assertEquals(WatchCommand.Stealth, quotedStealth)
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
