package github.xitadoptus.macro

import com.mojang.brigadier.arguments.StringArgumentType
import github.xitadoptus.macro.engine.MacroRuntime
import github.xitadoptus.macro.engine.MacroStorage
import github.xitadoptus.macro.util.ClientUtils
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import java.util.Locale

object MacroCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            listOf("macro", "macros", "macroengine").forEach { name ->
                dispatcher.register(
                    literal(name)
                        .executes {
                            MacroMod.openGui()
                            1
                        }
                        .then(literal("open").executes { open() })
                        .then(literal("gui").executes { open() })
                        .then(literal("editor").executes { open() })
                        .then(literal("help").executes { help() })
                        .then(literal("?").executes { help() })
                        .then(literal("list").executes { listMacros() })
                        .then(literal("ls").executes { listMacros() })
                        .then(literal("reload").executes { reload() })
                        .then(literal("save").executes { save() })
                        .then(
                            literal("run")
                                .then(argument("name", StringArgumentType.greedyString()).executes {
                                    runMacro(StringArgumentType.getString(it, "name"))
                                })
                        )
                )
            }
        }
    }

    private fun open(): Int {
        MacroMod.openGui()
        return 1
    }

    private fun help(): Int {
        ClientUtils.displayChatMessage("\u00A7a[MacroEngine] \u00A7f/macro \u00A77opens the editor.")
        ClientUtils.displayChatMessage("\u00A7a[MacroEngine] \u00A7f/macro list \u00A77shows saved macros.")
        ClientUtils.displayChatMessage("\u00A7a[MacroEngine] \u00A7f/macro run <name> \u00A77runs a saved macro.")
        return 1
    }

    private fun listMacros(): Int {
        MacroRuntime.ensureLoaded()
        val macros = MacroStorage.config.macros
        if (macros.isEmpty()) {
            ClientUtils.displayChatMessage("\u00A7e[MacroEngine] No macros saved. Use \u00A7f/macro\u00A7e, click New, write a script, then Save.")
            return 1
        }

        val names = macros.joinToString(", ") {
            if (it.enabled) it.name else "${it.name} (disabled)"
        }
        ClientUtils.displayChatMessage("\u00A7a[MacroEngine] Macros: \u00A7f$names")
        return 1
    }

    private fun reload(): Int {
        MacroRuntime.reload()
        ClientUtils.displayChatMessage("\u00A7a[MacroEngine] Reloaded.")
        return 1
    }

    private fun save(): Int {
        MacroRuntime.save()
        ClientUtils.displayChatMessage("\u00A7a[MacroEngine] Saved.")
        return 1
    }

    private fun runMacro(rawName: String): Int {
        val name = rawName.trim()
        if (name.isBlank()) {
            ClientUtils.displayChatMessage("\u00A7e[MacroEngine] Usage: \u00A7f/macro run <name>")
            return listMacros()
        }

        MacroRuntime.ensureLoaded()
        val macro = MacroStorage.config.macros.firstOrNull {
            it.name.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT)
        }

        if (macro == null) {
            ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Macro not found: \u00A7f$name")
            return 1
        }

        MacroRuntime.runMacro(macro)
        return 1
    }
}
