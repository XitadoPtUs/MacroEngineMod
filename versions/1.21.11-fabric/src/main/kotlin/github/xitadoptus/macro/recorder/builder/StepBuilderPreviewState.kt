package github.xitadoptus.macro.recorder.builder

import java.util.concurrent.ConcurrentHashMap

data class BuilderPreviewSnapshot(
    val waypoints: List<RouteWaypoint> = emptyList(),
    val target: BlockTarget? = null
)

object StepBuilderPreviewState {
    private val previews = ConcurrentHashMap<String, BuilderPreviewSnapshot>()

    fun register(id: String, waypoints: List<RouteWaypoint>, target: BlockTarget?) {
        previews[id] = BuilderPreviewSnapshot(waypoints.toList(), target)
    }

    fun register(id: String, builder: StepBuilderMacro) {
        register(id, routeFrom(builder), targetFrom(builder))
    }

    fun unregister(id: String) {
        previews.remove(id)
    }

    fun unregisterMatching(name: String) {
        previews.keys.filter { it.startsWith(name, ignoreCase = true) || it.contains(name, ignoreCase = true) }.forEach(previews::remove)
    }

    fun clearAll() {
        previews.clear()
    }

    fun snapshot(): BuilderPreviewSnapshot {
        val values = previews.values.toList()
        return BuilderPreviewSnapshot(
            waypoints = values.flatMap { it.waypoints },
            target = values.firstNotNullOfOrNull { it.target }
        )
    }

    private fun routeFrom(builder: StepBuilderMacro): List<RouteWaypoint> {
        return builder.steps.flatMap { step ->
            when (step.type) {
                BuilderStepType.ROUTE_TO_SELL, BuilderStepType.RETURN_ROUTE -> step.route
                else -> emptyList()
            }
        }
    }

    private fun targetFrom(builder: StepBuilderMacro): BlockTarget? {
        return builder.steps.firstNotNullOfOrNull { it.target }
    }
}
