package github.xitadoptus.macro.recorder

object MacroRecorderScriptBuilder {
    fun build(actions: List<RecordedMacroAction>): String {
        if (actions.isEmpty()) {
            return "$" + "$" + "{\n" +
                "LOG(\"&e[MacroEngine] Recorder captured no actions\");\n" +
                "}" + "$" + "$"
        }

        val lines = mutableListOf<String>()
        var previousTime = 0L

        lines += "LOG(\"&a[MacroEngine] Recorded macro started\");"
        actions
            .filter { it.statement.isNotBlank() }
            .sortedBy { it.timeMillis }
            .forEach { action ->
                val wait = (action.timeMillis - previousTime).coerceAtLeast(0L)
                if (wait > 0L) lines += "WAIT(${wait}ms);"
                lines += action.statement.trim().removeSuffix(";") + ";"
                previousTime = action.timeMillis
            }
        lines += "LOG(\"&a[MacroEngine] Recorded macro finished\");"

        return "$" + "$" + "{\n" + lines.joinToString("\n") + "\n}" + "$" + "$"
    }
}
