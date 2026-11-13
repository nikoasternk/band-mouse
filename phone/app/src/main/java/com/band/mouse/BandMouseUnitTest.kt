package com.band.mouse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

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
    fun test360DegreeContinuousScrollVectors() {
        val delta = MouseEngine.computeScrollDelta(10, -15, 8.0f)
        println("[TEST-LOG] 360 scroll delta for (10, -15) at 8x: $delta")
        assertTrue(delta.first > 0f)
        assertTrue(delta.second < 0f)

        val initStep = MouseEngine.computeInitialScrollStep(540f, 1200f, delta.first, delta.second, 24f, 1080, 2400)
        println("[TEST-LOG] Initial 360 scroll step: $initStep")
        assertTrue(initStep.endX > 540f)
        assertTrue(initStep.endY < 1200f)
        assertFalse(initStep.needsRecycle)

        val contStep = MouseEngine.computeContinuationScrollStep(initStep.endX, initStep.endY, 12f, 18f, 1080, 2400)
        println("[TEST-LOG] Continuation 360 scroll step: $contStep")
        assertEquals(initStep.endX + 12f, contStep.endX, 0.01f)
        assertEquals(initStep.endY + 18f, contStep.endY, 0.01f)
        assertFalse(contStep.needsRecycle)

        val edgeStep = MouseEngine.computeContinuationScrollStep(540f, 100f, 0f, -50f, 1080, 2400)
        println("[TEST-LOG] Edge boundary recycle check: $edgeStep")
        assertTrue(edgeStep.needsRecycle)
    }

    @Test
    fun testWatchPacketProtocolParsing() {
        val moveCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"m","dx":7,"dy":-4}""")
        assertEquals(WatchCommand.Move(7, -4), moveCmd)

        val scrollMoveCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"s_move","dx":-5,"dy":12}""")
        assertEquals(WatchCommand.ScrollMove(-5, 12), scrollMoveCmd)

        val scrollEndCmd = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"s_end"}""")
        assertEquals(WatchCommand.ScrollEnd, scrollEndCmd)

        val quotedStealth = MouseEngine.parseWatchPacket("\"{\\\"tag\\\":\\\"mouse\\\",\\\"act\\\":\\\"stealth\\\"}\"")
        assertEquals(WatchCommand.Stealth, quotedStealth)

        val stateOpen = MouseEngine.parseWatchPacket("""{"tag":"mouse","act":"state","open":true}""")
        assertEquals(WatchCommand.State(true), stateOpen)

        val hs = MouseEngine.parseWatchPacket("""{"tag":"handshake","step":1}""")
        assertEquals(WatchCommand.Handshake(1), hs)
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
