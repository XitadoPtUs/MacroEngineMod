package github.xitadoptus.macro.recorder.builder

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaypointMathTest {
    @Test
    fun detectsReachedWaypointByDistance() {
        val waypoint = RouteWaypoint(x = 10.0, y = 64.0, z = 10.0, radius = 1.5)

        assertTrue(WaypointMath.reached(10.5, 64.0, 10.5, waypoint))
        assertFalse(WaypointMath.reached(13.0, 64.0, 10.0, waypoint))
    }

    @Test
    fun calculatesYawTowardPositiveZ() {
        val yaw = WaypointMath.yawTo(0.0, 0.0, 0.0, 10.0)

        assertTrue(abs(yaw - 0f) < 0.01f)
    }

    @Test
    fun calculatesHorizontalDistance() {
        assertEquals(5.0, WaypointMath.horizontalDistance(0.0, 0.0, 3.0, 4.0))
    }
}
