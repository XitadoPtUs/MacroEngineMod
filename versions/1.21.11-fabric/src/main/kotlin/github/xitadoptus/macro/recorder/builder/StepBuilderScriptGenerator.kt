package github.xitadoptus.macro.recorder.builder

import java.util.Locale

object StepBuilderScriptGenerator {
    fun generate(builder: StepBuilderMacro): String {
        val lines = mutableListOf<String>()
        lines += "LOG(\"&a[MacroEngine] Step builder macro started\");"
        lines += "DO();"
        lines += "LOG(\"&e[MacroEngine] Waiting for inventory to be full\");"

        builder.steps.forEach { step ->
            when (step.type) {
                BuilderStepType.WAIT_INVENTORY_FULL -> lines += "WAITINVENTORYFULL();"
                BuilderStepType.COMMAND -> commandLine(step.command)?.let { lines += it }
                BuilderStepType.ROUTE_TO_SELL, BuilderStepType.RETURN_ROUTE -> step.route.forEach { lines += waypointLine(it) }
                BuilderStepType.SELL_TARGET -> lines.addAll(sellTargetLines(step))
                BuilderStepType.LOOP -> Unit
            }
        }

        lines += "LOOP();"
        return "$" + "$" + "{\n" + lines.joinToString("\n") + "\n}" + "$" + "$"
    }

    private fun commandLine(command: String): String? {
        val clean = command.trim()
        if (clean.isBlank()) return null
        return "CHAT(\"${escape(clean)}\");"
    }

    private fun waypointLine(waypoint: RouteWaypoint): String {
        return "GOTO(${num(waypoint.x)}, ${num(waypoint.y)}, ${num(waypoint.z)}, ${num(waypoint.radius)}, ${waypoint.timeoutMillis}, ${waypoint.sprint});"
    }

    private fun sellTargetLines(step: BuilderStep): List<String> {
        val target = step.target ?: return emptyList()
        val action = step.sellAction
        val key = if (action.button == ClickButton.LEFT) "attack" else "use"
        val lines = mutableListOf<String>()
        lines += "AIMTO(${num(target.x + 0.5)}, ${num(target.y + 0.5)}, ${num(target.z + 0.5)});"
        if (action.shift) lines += "KEYDOWN(sneak);"
        if (action.control) lines += "KEYDOWN(LCTRL);"
        if (action.alt) lines += "KEYDOWN(LALT);"
        lines += "KEYDOWN($key);"
        lines += "WAIT(150ms);"
        lines += "KEYUP($key);"
        if (action.alt) lines += "KEYUP(LALT);"
        if (action.control) lines += "KEYUP(LCTRL);"
        if (action.shift) lines += "KEYUP(sneak);"
        return lines
    }

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun num(value: Double): String {
        return String.format(Locale.ROOT, "%.2f", value)
    }
}
