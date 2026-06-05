package github.xitadoptus.macro.recorder.builder

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteFlightDecisionTest {
    @Test
    fun ascendsWhenFlyingAndObstacleBlocksForwardRoute() {
        assertEquals(
            FlightAction.ASCEND,
            RouteFlightDecision.choose(blockedAhead = true, flying = true, playerY = 64.0, waypointY = 66.0)
        )
    }

    @Test
    fun descendsWhenFlyingTowardLowerWaypointAndBlockedAhead() {
        assertEquals(
            FlightAction.DESCEND,
            RouteFlightDecision.choose(blockedAhead = true, flying = true, playerY = 66.0, waypointY = 64.0)
        )
    }

    @Test
    fun keepsLevelWhenThereIsNoForwardObstacle() {
        assertEquals(
            FlightAction.NONE,
            RouteFlightDecision.choose(blockedAhead = false, flying = true, playerY = 64.0, waypointY = 64.0)
        )
    }

    @Test
    fun descendsTowardLowerWaypointWhileFlying() {
        assertEquals(
            FlightAction.DESCEND,
            RouteFlightDecision.choose(blockedAhead = false, flying = true, playerY = 66.0, waypointY = 64.0)
        )
    }
}
