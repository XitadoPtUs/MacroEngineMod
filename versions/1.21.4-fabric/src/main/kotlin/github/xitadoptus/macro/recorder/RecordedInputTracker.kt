package github.xitadoptus.macro.recorder

class RecordedInputTracker(inputNames: List<String>) {
    private val names = inputNames.distinct()
    private val previous = names.associateWith { false }.toMutableMap()

    fun update(timeMillis: Long, current: Map<String, Boolean>): List<RecordedMacroAction> {
        val actions = mutableListOf<RecordedMacroAction>()
        names.forEach { name ->
            val pressed = current[name] == true
            val wasPressed = previous[name] == true
            if (pressed != wasPressed) {
                actions += RecordedMacroAction(timeMillis, "${if (pressed) "KEYDOWN" else "KEYUP"}($name)")
                previous[name] = pressed
            }
        }
        return actions
    }

    fun releaseHeld(timeMillis: Long): List<RecordedMacroAction> {
        val actions = mutableListOf<RecordedMacroAction>()
        names.forEach { name ->
            if (previous[name] == true) {
                actions += RecordedMacroAction(timeMillis, "KEYUP($name)")
                previous[name] = false
            }
        }
        return actions
    }
}
