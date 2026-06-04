package github.xitadoptus.macro.recorder.builder

import kotlin.math.cos
import kotlin.math.sin

data class SmokePoint(
    val x: Double,
    val y: Double,
    val z: Double
)

data class SmokeLine(
    val start: SmokePoint,
    val end: SmokePoint,
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int
)

object WaypointSmokeShape {
    fun lines(waypoint: RouteWaypoint, timeMillis: Long): List<SmokeLine> {
        val lines = mutableListOf<SmokeLine>()
        val phase = (timeMillis % 2400L).toDouble() / 2400.0 * Math.PI * 2.0
        val centerX = waypoint.x
        val centerY = waypoint.y + 0.08
        val centerZ = waypoint.z

        for (index in 0 until 20) {
            val t1 = index / 20.0
            val t2 = (index + 1) / 20.0
            lines += SmokeLine(
                start = point(centerX, centerY, centerZ, phase, t1),
                end = point(centerX, centerY, centerZ, phase, t2),
                red = 45,
                green = if (waypoint.manual) 210 else 170,
                blue = 255,
                alpha = if (waypoint.manual) 230 else 175
            )
        }

        return lines
    }

    private fun point(x: Double, y: Double, z: Double, phase: Double, t: Double): SmokePoint {
        val angle = phase + t * Math.PI * 4.5
        val radius = 0.16 + t * 0.28
        return SmokePoint(
            x = x + cos(angle) * radius,
            y = y + t * 1.05,
            z = z + sin(angle) * radius
        )
    }
}
