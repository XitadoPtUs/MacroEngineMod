package github.xitadoptus.macro.engine

import github.xitadoptus.macro.recorder.builder.StepBuilderMacro

data class MacroEntry(
    var name: String = "New Macro",
    var key: String = "NONE",
    var script: String = "",
    var enabled: Boolean = true,
    var builder: StepBuilderMacro? = null
)

data class MacroEventBinding(
    var event: String = "onJoinGame",
    var script: String = "",
    var enabled: Boolean = true
)

data class MacroConfig(
    val macros: MutableList<MacroEntry> = mutableListOf(),
    val events: MutableList<MacroEventBinding> = mutableListOf(),
    var recorderStopKey: String = "RSHIFT",
    var runtimeViewerKey: String = "V"
)
