package github.xitadoptus.macro.recorder.builder

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StuckDetectorTest {
    @Test
    fun reportsStuckAfterPositionBarelyChangesForThreshold() {
        val detector = StuckDetector(thresholdMillis = 800L, minMovement = 0.15)

        assertFalse(detector.update(0L, 0.0, 64.0, 0.0))
        assertFalse(detector.update(400L, 0.02, 64.0, 0.01))
        assertTrue(detector.update(900L, 0.03, 64.0, 0.01))
    }

    @Test
    fun movementResetsStuckTimer() {
        val detector = StuckDetector(thresholdMillis = 800L, minMovement = 0.15)

        detector.update(0L, 0.0, 64.0, 0.0)
        detector.update(700L, 1.0, 64.0, 0.0)

        assertFalse(detector.update(900L, 1.01, 64.0, 0.0))
    }
}
