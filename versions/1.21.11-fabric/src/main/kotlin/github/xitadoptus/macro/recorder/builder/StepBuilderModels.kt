package github.xitadoptus.macro.recorder.builder

data class StepBuilderMacro(
    var name: String = "Step Builder Macro",
    var trigger: BuilderTrigger = BuilderTrigger.INVENTORY_FULL,
    var steps: MutableList<BuilderStep> = mutableListOf()
)

data class BuilderStep(
    var type: BuilderStepType = BuilderStepType.WAIT_INVENTORY_FULL,
    var title: String = type.defaultTitle,
    var command: String = "",
    var route: MutableList<RouteWaypoint> = mutableListOf(),
    var target: BlockTarget? = null,
    var sellAction: SellAction = SellAction(),
    var timeoutMillis: Long = 15000L
)

data class RouteWaypoint(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
    var yaw: Float = 0f,
    var pitch: Float = 0f,
    var manual: Boolean = false,
    var radius: Double = 1.25,
    var sprint: Boolean = true,
    var jumpAllowed: Boolean = true,
    var timeoutMillis: Long = 15000L
)

data class BlockTarget(
    var x: Int = 0,
    var y: Int = 0,
    var z: Int = 0
)

data class SellAction(
    var button: ClickButton = ClickButton.LEFT,
    var shift: Boolean = true,
    var control: Boolean = false,
    var alt: Boolean = false
)

enum class BuilderTrigger {
    INVENTORY_FULL
}

enum class BuilderStepType(val defaultTitle: String) {
    WAIT_INVENTORY_FULL("Wait Inventory Full"),
    COMMAND("Command"),
    ROUTE_TO_SELL("Route To Sell"),
    SELL_TARGET("Sell Target"),
    RETURN_ROUTE("Return Route"),
    LOOP("Loop")
}

enum class ClickButton {
    LEFT,
    RIGHT
}
