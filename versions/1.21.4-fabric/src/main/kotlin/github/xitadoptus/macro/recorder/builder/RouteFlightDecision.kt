package github.xitadoptus.macro.recorder.builder

enum class FlightAction {
    NONE,
    ASCEND,
    DESCEND
}

object RouteFlightDecision {
    fun choose(blockedAhead: Boolean, flying: Boolean, playerY: Double, waypointY: Double): FlightAction {
        if (!flying) return FlightAction.NONE
        if (waypointY < playerY - 0.55) return FlightAction.DESCEND
        if (blockedAhead) return FlightAction.ASCEND
        return FlightAction.NONE
    }
}
