package github.xitadoptus.macro.recorder.builder

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class WaypointSmokeShapeTest {
    @Test
    fun createsBlueSmokeLinesAroundWaypoint() {
        val lines = WaypointSmokeShape.lines(RouteWaypoint(x = 1.0, y = 2.0, z = 3.0), 1000L)

        assertTrue(lines.size >= 16)
        assertTrue(lines.all { it.blue > it.red && it.blue >= it.green })
        assertTrue(abs(lines.first().start.x - 1.0) < 0.5)
        assertTrue(lines.maxOf { it.end.y } > 2.75)
    }
}
