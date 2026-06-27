package github.xitadoptus.macro.engine

import github.xitadoptus.macro.gui.GuiMacroRuntimeViewer
import github.xitadoptus.macro.util.ClientUtils
import github.xitadoptus.macro.util.KeyboardUtils
import github.xitadoptus.macro.util.MinecraftInstance
import net.minecraft.client.settings.KeyBinding
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object MacroRuntime : MinecraftInstance() {
    private val previousKeyState = ConcurrentHashMap<String, Boolean>()
    private val running: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val runningLabels = ConcurrentHashMap<String, String>()
    private val threadCounter = AtomicInteger()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "MacroEngine-${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    val variables: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    val arrays: ConcurrentHashMap<String, MutableList<String>> = ConcurrentHashMap()
    val stores: ConcurrentHashMap<String, MutableList<String>> = ConcurrentHashMap()
    val guiProperties: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    val labels: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    @Volatile
    var currentConfigName: String = "default"
    @Volatile
    private var loaded = false

    @Volatile
    private var lastWorldPresent = false
    private var runtimeViewerWasPressed = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            MacroStorage.load()
            loaded = true
        }
    }

    fun save() {
        ensureLoaded()
        MacroStorage.save()
    }

    fun reload() {
        MacroStorage.load()
        loaded = true
    }

    fun runMacro(macro: MacroEntry, reason: String = macro.name, locals: Map<String, String> = emptyMap()) {
        if (!macro.enabled || macro.script.isBlank()) return
        runScript(macro.script, reason, locals)
    }

    fun runScript(script: String, reason: String = "script", locals: Map<String, String> = emptyMap()) {
        ensureLoaded()
        val id = "$reason-${UUID.randomUUID()}"
        running += id
        runningLabels[id] = reason

        executor.execute {
            try {
                MacroScriptEngine(id, running, locals).execute(script)
            } catch (t: Throwable) {
                ClientUtils.displayChatMessage("§c[MacroEngine] $reason failed: ${t.message ?: t.javaClass.simpleName}")
                ClientUtils.logError("[MacroEngine] Macro '$reason' failed", t)
            } finally {
                running -= id
                runningLabels.remove(id)
            }
        }
    }

    fun isRunning(name: String): Boolean {
        return running.any { it.startsWith(name, ignoreCase = true) }
    }

    fun runningIds(): List<String> {
        return running.toList()
    }

    fun runningNames(): List<String> {
        return running.toList().map { runningLabels[it] ?: it.substringBeforeLast("-") }.distinct()
    }

    fun stopMatching(name: String): Boolean {
        if (name.isBlank()) return false
        val before = running.size
        running.removeIf {
            it.startsWith(name, ignoreCase = true) ||
                it.contains(name, ignoreCase = true) ||
                (runningLabels[it]?.contains(name, ignoreCase = true) == true)
        }
        val stopped = running.size != before
        if (stopped) {
            runningLabels.entries.removeIf { it.value.contains(name, ignoreCase = true) }
            releaseHeldInputs()
        }
        return stopped
    }

    fun stopMacro(name: String): Boolean {
        if (name.isBlank()) return false
        val ids = running.filter { id ->
            val label = runningLabels[id] ?: id.substringBeforeLast("-")
            label.equals(name, ignoreCase = true)
        }
        if (ids.isEmpty()) return false
        ids.forEach { id ->
            running.remove(id)
            runningLabels.remove(id)
        }
        releaseHeldInputs()
        return true
    }

    fun stopAll(showMessage: Boolean = true): Boolean {
        if (running.isEmpty()) return false
        running.clear()
        runningLabels.clear()
        previousKeyState.clear()
        releaseHeldInputs()
        if (showMessage) ClientUtils.displayChatMessage("§c[MacroEngine] All macros stopped.")
        return true
    }

    fun fireEvent(event: String, locals: Map<String, String> = emptyMap()) {
        ensureLoaded()
        MacroStorage.config.events
            .filter { it.enabled && it.event.equals(event, ignoreCase = true) && it.script.isNotBlank() }
            .forEach { runScript(it.script, it.event, locals) }
    }

    @SubscribeEvent
    fun onTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        ensureLoaded()
        updateWorldState()
        if (mc.theWorld == null || mc.thePlayer == null) {
            previousKeyState.clear()
            runtimeViewerWasPressed = false
            return
        }
        handleRuntimeViewerKey()
        GuiMacroRuntimeViewer.onClientTick()
        if (mc.currentScreen != null) return

        MacroStorage.config.macros
            .filter { it.enabled && it.key != "NONE" }
            .forEach { macro ->
                val key = KeyboardUtils.normalizeKey(macro.key)
                val pressed = KeyboardUtils.isKeyPressed(key)
                val before = previousKeyState.put(key, pressed) ?: false
                if (pressed && !before) {
                    toggleMacro(macro, key)
                }
            }
    }

    @SubscribeEvent
    fun onChat(event: ClientChatReceivedEvent) {
        ensureLoaded()
        val message = event.message?.formattedText ?: return
        fireEvent("onChat", mapOf("CHAT" to message, "CHATCLEAN" to net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(message)))
    }

    private fun updateWorldState() {
        val present = mc.theWorld != null && mc.thePlayer != null
        if (present && !lastWorldPresent) {
            fireEvent("onJoinGame")
            fireEvent("onWorldChange")
        } else if (!present && lastWorldPresent) {
            if (stopAll(showMessage = false)) {
                ClientUtils.displayChatMessage("§c[MacroEngine] Left the world — all running macros stopped.")
            }
            fireEvent("onWorldChange")
        }
        lastWorldPresent = present
    }

    private fun handleRuntimeViewerKey() {
        val viewerKey = KeyboardUtils.normalizeKey(MacroStorage.config.runtimeViewerKey)
        if (!KeyboardUtils.isValidKeyName(viewerKey) || viewerKey == "NONE") return
        val pressed = KeyboardUtils.isInputPressed(viewerKey)
        if (pressed && !runtimeViewerWasPressed) {
            GuiMacroRuntimeViewer.toggle()
        }
        runtimeViewerWasPressed = pressed
    }

    private fun toggleMacro(macro: MacroEntry, key: String) {
        if (stopMacro(macro.name)) {
            ClientUtils.displayChatMessage("Â§c[MacroEngine] Macro stopped: Â§f${macro.name}")
            return
        }
        runMacro(
            macro,
            locals = mapOf(
                "KEYNAME" to key,
                "KEYID" to KeyboardUtils.keyCode(key).toString()
            )
        )
    }

    private fun releaseHeldInputs() {
        val settings = mc.gameSettings ?: return
        listOf(
            settings.keyBindForward,
            settings.keyBindBack,
            settings.keyBindLeft,
            settings.keyBindRight,
            settings.keyBindJump,
            settings.keyBindSneak,
            settings.keyBindSprint,
            settings.keyBindAttack,
            settings.keyBindUseItem
        ).forEach { KeyBinding.setKeyBindState(it.keyCode, false) }
        mc.thePlayer?.isSprinting = false
    }
}
