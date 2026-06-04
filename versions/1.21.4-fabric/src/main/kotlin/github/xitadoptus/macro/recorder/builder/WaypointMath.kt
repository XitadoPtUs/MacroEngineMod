package github.xitadoptus.macro.recorder.builder

import kotlin.math.atan2
import kotlin.math.sqrt

object WaypointMath {
    fun reached(x: Double, y: Double, z: Double, waypoint: RouteWaypoint): Boolean {
        val horizontal = horizontalDistance(x, z, waypoint.x, waypoint.z)
        val vertical = kotlin.math.abs(y - waypoint.y)
        return horizontal <= waypoint.radius && vertical <= waypoint.radius.coerceAtLeast(1.5)
    }

    fun horizontalDistance(x1: Double, z1: Double, x2: Double, z2: Double): Double {
        val dx = x2 - x1
        val dz = z2 - z1
        return sqrt(dx * dx + dz * dz)
    }

    fun yawTo(fromX: Double, fromZ: Double, toX: Double, toZ: Double): Float {
        val dx = toX - fromX
        val dz = toZ - fromZ
        return (atan2(dz, dx) * 180.0 / Math.PI - 90.0).toFloat()
    }
}
