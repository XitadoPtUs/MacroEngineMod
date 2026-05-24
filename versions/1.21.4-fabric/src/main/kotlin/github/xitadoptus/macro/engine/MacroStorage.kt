package github.xitadoptus.macro.engine

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import github.xitadoptus.macro.MacroMod
import github.xitadoptus.macro.util.ClientUtils
import java.io.File

object MacroStorage {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    val macrosDir: File
        get() = File(MacroMod.configDir, "macros")

    private val configFile: File
        get() = File(macrosDir, "macros.json")

    var config = MacroConfig()
        private set

    fun load() {
        ensureFolders()
        if (!configFile.exists()) {
            config = MacroConfig()
            save()
            return
        }

        runCatching {
            val json = JsonParser.parseString(configFile.readText(Charsets.UTF_8))
            config = gson.fromJson(json, MacroConfig::class.java) ?: MacroConfig()
            config.macros.removeAll { it.name.isBlank() }
            config.events.removeAll { it.event.isBlank() }
        }.onFailure {
            ClientUtils.logError("[MacroEngine] Failed to load macros.json", it)
            config = MacroConfig()
        }
    }

    fun save() {
        ensureFolders()
        runCatching {
            configFile.writeText(gson.toJson(config), Charsets.UTF_8)
            exportTextFiles()
        }.onFailure {
            ClientUtils.logError("[MacroEngine] Failed to save macros.json", it)
        }
    }

    fun scriptFile(name: String): File {
        val clean = name.trim().removeSuffix(".txt")
        return File(macrosDir, "$clean.txt")
    }

    fun readScriptFile(name: String): String? {
        val direct = File(macrosDir, name.trim())
        val file = if (direct.exists()) direct else scriptFile(name)
        return if (file.exists() && file.isFile) file.readText(Charsets.UTF_8) else null
    }

    private fun ensureFolders() {
        if (!macrosDir.exists()) macrosDir.mkdirs()
    }

    private fun exportTextFiles() {
        config.macros.forEach { macro ->
            val safeName = macro.name.replace(Regex("[^A-Za-z0-9_. -]"), "_").ifBlank { "macro" }
            scriptFile(safeName).writeText(macro.script, Charsets.UTF_8)
        }
    }
}
