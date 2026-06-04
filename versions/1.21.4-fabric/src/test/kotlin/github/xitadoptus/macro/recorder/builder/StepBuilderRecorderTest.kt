package github.xitadoptus.macro.recorder.builder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StepBuilderRecorderTest {
    @Test
    fun buildsMacroWithCommandRouteTargetAndReturn() {
        val recorder = StepBuilderRecorder()

        recorder.start("Seller")
        recorder.setCommand("/vip")
        recorder.addWaypoint(RouteWaypoint(x = 10.0, y = 64.0, z = 10.0, manual = false))
        recorder.addWaypoint(RouteWaypoint(x = 12.0, y = 64.0, z = 12.0, manual = true))
        recorder.setSellTarget(BlockTarget(13, 65, 13), SellAction())
        val macro = recorder.finish()

        assertEquals("Seller", macro.name)
        assertNotNull(macro.builder)
        assertEquals(true, macro.script.contains("WAITINVENTORYFULL();"))
        assertEquals(true, macro.script.contains("CHAT(\"/vip\");"))
        assertEquals(true, macro.script.contains("AIMTO(13.50, 65.50, 13.50);"))
    }

    @Test
    fun recordsAutomaticWaypointOnlyAfterMinimumDistance() {
        val recorder = StepBuilderRecorder(autoWaypointDistance = 2.0)

        recorder.start("Auto")
        assertEquals(true, recorder.recordAutomaticWaypoint(RouteWaypoint(x = 0.0, y = 64.0, z = 0.0)))
        assertEquals(false, recorder.recordAutomaticWaypoint(RouteWaypoint(x = 1.0, y = 64.0, z = 0.0)))
        assertEquals(true, recorder.recordAutomaticWaypoint(RouteWaypoint(x = 2.5, y = 64.0, z = 0.0)))
    }

    @Test
    fun exposesCapturedRouteAndTargetForPreview() {
        val recorder = StepBuilderRecorder()
        val waypoint = RouteWaypoint(x = 4.0, y = 65.0, z = 8.0)
        val target = BlockTarget(6, 66, 10)

        recorder.start("Preview")
        recorder.addWaypoint(waypoint)
        recorder.setSellTarget(target, SellAction())

        assertEquals(listOf(waypoint), recorder.previewWaypoints())
        assertEquals(target, recorder.previewTarget())
    }
}
