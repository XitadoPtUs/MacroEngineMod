package github.xitadoptus.macro.recorder.builder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepBuilderPreviewStateTest {
    @Test
    fun storesAndClearsRunningBuilderPreview() {
        val waypoint = RouteWaypoint(x = 5.0, y = 64.0, z = 7.0)
        val target = BlockTarget(6, 65, 8)

        StepBuilderPreviewState.clearAll()
        StepBuilderPreviewState.register("macro-1", listOf(waypoint), target)

        val snapshot = StepBuilderPreviewState.snapshot()
        assertEquals(listOf(waypoint), snapshot.waypoints)
        assertEquals(target, snapshot.target)

        StepBuilderPreviewState.unregister("macro-1")
        assertTrue(StepBuilderPreviewState.snapshot().waypoints.isEmpty())
    }
}
