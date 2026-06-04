package github.xitadoptus.macro.recorder.builder

import github.xitadoptus.macro.engine.MacroEntry

class StepBuilderRecorder(private val autoWaypointDistance: Double = 2.0) {
    private var name = "Step Builder Macro"
    private var command = ""
    private val routeToSell = mutableListOf<RouteWaypoint>()
    private var sellTarget: BlockTarget? = null
    private var sellAction = SellAction()

    fun start(name: String) {
        this.name = name.ifBlank { "Step Builder Macro" }
        command = ""
        routeToSell.clear()
        sellTarget = null
        sellAction = SellAction()
    }

    fun setCommand(command: String) {
        this.command = command.trim()
    }

    fun addWaypoint(waypoint: RouteWaypoint) {
        routeToSell += waypoint
    }

    fun recordAutomaticWaypoint(waypoint: RouteWaypoint): Boolean {
        val last = routeToSell.lastOrNull()
        if (last != null && WaypointMath.horizontalDistance(last.x, last.z, waypoint.x, waypoint.z) < autoWaypointDistance) {
            return false
        }
        addWaypoint(waypoint.copy(manual = false))
        return true
    }

    fun setSellTarget(target: BlockTarget, action: SellAction) {
        sellTarget = target
        sellAction = action
    }

    fun previewWaypoints(): List<RouteWaypoint> {
        return routeToSell.toList()
    }

    fun previewTarget(): BlockTarget? {
        return sellTarget
    }

    fun finish(): MacroEntry {
        val returnRoute = routeToSell.asReversed().map { it.copy() }.toMutableList()
        val builder = StepBuilderMacro(
            name = name,
            steps = mutableListOf(
                BuilderStep(type = BuilderStepType.WAIT_INVENTORY_FULL),
                BuilderStep(type = BuilderStepType.COMMAND, command = command),
                BuilderStep(type = BuilderStepType.ROUTE_TO_SELL, route = routeToSell.toMutableList()),
                BuilderStep(type = BuilderStepType.SELL_TARGET, target = sellTarget, sellAction = sellAction),
                BuilderStep(type = BuilderStepType.RETURN_ROUTE, route = returnRoute),
                BuilderStep(type = BuilderStepType.LOOP)
            )
        )
        return MacroEntry(
            name = name,
            key = "NONE",
            enabled = true,
            builder = builder,
            script = StepBuilderScriptGenerator.generate(builder)
        )
    }
}
