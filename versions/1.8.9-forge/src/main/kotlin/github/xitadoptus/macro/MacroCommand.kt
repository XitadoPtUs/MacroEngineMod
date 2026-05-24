package github.xitadoptus.macro

import github.xitadoptus.macro.engine.MacroRuntime
import github.xitadoptus.macro.engine.MacroStorage
import github.xitadoptus.macro.util.ClientUtils
import net.minecraft.command.CommandBase
import net.minecraft.command.ICommandSender
import java.util.Locale

class MacroCommand : CommandBase() {
    override fun getCommandName(): String = "macro"

    override fun getCommandAliases(): MutableList<String> {
        return mutableListOf("macros", "macroengine")
    }

    override fun getCommandUsage(sender: ICommandSender): String {
        return "/macro <open|help|list|reload|save|run <name>>"
    }

    override fun getRequiredPermissionLevel(): Int = 0

    override fun processCommand(sender: ICommandSender, args: Array<String>) {
        when (args.getOrNull(0)?.toLowerCase(Locale.ROOT) ?: "open") {
            "open", "gui", "opengui", "editor" -> MacroMod.openGui()
            "help", "?" -> showHelp()
            "list", "ls" -> listMacros()
            "reload" -> {
                MacroRuntime.reload()
                ClientUtils.displayChatMessage("§a[MacroEngine] Reloaded.")
            }
            "save" -> {
                MacroRuntime.save()
                ClientUtils.displayChatMessage("§a[MacroEngine] Saved.")
            }
            "run" -> runMacro(args.drop(1).joinToString(" "))
            else -> {
                ClientUtils.displayChatMessage("§c[MacroEngine] Unknown command: §f${args[0]}")
                showHelp()
            }
        }
    }

    private fun showHelp() {
        ClientUtils.displayChatMessage("§a[MacroEngine] §f/macro §7opens the editor.")
        ClientUtils.displayChatMessage("§a[MacroEngine] §f/macro list §7shows saved macros.")
        ClientUtils.displayChatMessage("§a[MacroEngine] §f/macro run <name> §7runs a saved macro.")
    }

    private fun listMacros() {
        MacroRuntime.ensureLoaded()
        val macros = MacroStorage.config.macros
        if (macros.isEmpty()) {
            ClientUtils.displayChatMessage("§e[MacroEngine] No macros saved. Use §f/macro§e, click New, write a script, then Save.")
            return
        }

        val names = macros.joinToString(", ") {
            if (it.enabled) it.name else "${it.name} (disabled)"
        }
        ClientUtils.displayChatMessage("§a[MacroEngine] Macros: §f$names")
    }

    private fun runMacro(name: String) {
        if (name.isBlank()) {
            ClientUtils.displayChatMessage("§e[MacroEngine] Usage: §f/macro run <name>")
            listMacros()
            return
        }

        MacroRuntime.ensureLoaded()
        val macro = MacroStorage.config.macros.firstOrNull {
            it.name.equals(name, ignoreCase = true)
        }

        if (macro == null) {
            ClientUtils.displayChatMessage("§c[MacroEngine] Macro not found: §f$name")
            return
        }

        MacroRuntime.runMacro(macro)
    }
}
