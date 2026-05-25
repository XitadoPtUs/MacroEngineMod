package github.xitadoptus.macro.engine

data class MacroStatement(
    val name: String,
    val args: List<String>,
    val raw: String
)
