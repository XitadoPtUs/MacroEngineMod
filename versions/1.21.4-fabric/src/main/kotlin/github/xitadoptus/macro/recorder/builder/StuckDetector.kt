package github.xitadoptus.macro.recorder.builder

import kotlin.math.sqrt

class StuckDetector(
    private val thresholdMillis: Long = 1200L,
    private val minMovement: Double = 0.2
) {
    private var anchorTime = -1L
    private var anchorX = 0.0
    private var anchorY = 0.0
    private var anchorZ = 0.0

    fun update(nowMillis: Long, x: Double, y: Double, z: Double): Boolean {
        if (anchorTime < 0L) {
            reset(nowMillis, x, y, z)
            return false
        }

        if (distance(anchorX, anchorY, anchorZ, x, y, z) >= minMovement) {
            reset(nowMillis, x, y, z)
            return false
        }

        return nowMillis - anchorTime >= thresholdMillis
    }

    fun reset(nowMillis: Long, x: Double, y: Double, z: Double) {
        anchorTime = nowMillis
        anchorX = x
        anchorY = y
        anchorZ = z
    }

    private fun distance(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
