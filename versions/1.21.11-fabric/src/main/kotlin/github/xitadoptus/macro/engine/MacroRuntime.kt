package github.xitadoptus.macro.engine

import github.xitadoptus.macro.util.ClientUtils
import github.xitadoptus.macro.util.KeyboardUtils
import github.xitadoptus.macro.gui.MacroRuntimeViewerScreen
import github.xitadoptus.macro.recorder.MacroRecorder
import github.xitadoptus.macro.recorder.builder.StepBuilderCaptureController
import github.xitadoptus.macro.recorder.builder.RouteNavigator
import github.xitadoptus.macro.recorder.builder.StepBuilderMacro
import github.xitadoptus.macro.recorder.builder.StepBuilderPreviewState
import net.minecraft.client.Minecraft
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object MacroRuntime {
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

    @Volatile
    private var macroStopWasPressed = false

    @Volatile
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
        runScript(macro.script, reason, locals, macro.builder)
    }

    fun runScript(script: String, reason: String = "script", locals: Map<String, String> = emptyMap(), builder: StepBuilderMacro? = null) {
        ensureLoaded()
        val id = "$reason-${UUID.randomUUID()}"
        running += id
        runningLabels[id] = reason
        if (builder != null) StepBuilderPreviewState.register(id, builder)

        executor.execute {
            try {
                MacroScriptEngine(id, running, locals).execute(script)
            } catch (throwable: Throwable) {
                ClientUtils.displayChatMessage("\u00A7c[MacroEngine] $reason failed: ${throwable.message ?: throwable.javaClass.simpleName}")
                ClientUtils.logError("[MacroEngine] Macro '$reason' failed", throwable)
            } finally {
                running -= id
                runningLabels.remove(id)
                StepBuilderPreviewState.unregister(id)
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
            StepBuilderPreviewState.unregisterMatching(name)
            releaseHeldInputs(Minecraft.getInstance())
        }
        return stopped
    }

    fun stopAll(showMessage: Boolean = true): Boolean {
        val stopped = running.isNotEmpty()
        if (!stopped) return false
        running.clear()
        runningLabels.clear()
        StepBuilderPreviewState.clearAll()
        previousKeyState.clear()
        releaseHeldInputs(Minecraft.getInstance())
        if (showMessage) ClientUtils.displayChatMessage("\u00A7c[MacroEngine] All macros stopped.")
        return true
    }

    fun fireEvent(event: String, locals: Map<String, String> = emptyMap()) {
        ensureLoaded()
        MacroStorage.config.events
            .filter { it.enabled && it.event.equals(event, ignoreCase = true) && it.script.isNotBlank() }
            .forEach { runScript(it.script, it.event, locals) }
    }

    fun onClientTick(client: Minecraft) {
        ensureLoaded()
        updateWorldState(client)
        if (client.level == null || client.player == null) {
            previousKeyState.clear()
            macroStopWasPressed = false
            runtimeViewerWasPressed = false
            return
        }
        if (handleMacroStopKey(client)) return
        if (MacroRecorder.onClientTick(client)) return
        if (StepBuilderCaptureController.onClientTick(client)) return
        handleRuntimeViewerKey(client)
        if (client.screen != null) return

        MacroStorage.config.macros
            .filter { it.enabled && it.key != "NONE" }
            .forEach { macro ->
                val key = KeyboardUtils.normalizeKey(macro.key)
                val pressed = KeyboardUtils.isInputPressed(key)
                val before = previousKeyState.put(key, pressed) ?: false
                if (pressed && !before) {
                    runMacro(
                        macro,
                        locals = mapOf(
                            "KEYNAME" to key,
                            "KEYID" to KeyboardUtils.keyCode(key).toString()
                        )
                    )
                }
            }
    }

    private fun handleMacroStopKey(client: Minecraft): Boolean {
        val stopKey = KeyboardUtils.normalizeKey(MacroStorage.config.macroStopKey)
        if (!KeyboardUtils.isValidKeyName(stopKey) || stopKey == "NONE") return false
        val pressed = KeyboardUtils.isInputPressed(stopKey)
        val stopped = pressed && !macroStopWasPressed && stopAll()
        macroStopWasPressed = pressed
        if (stopped) releaseHeldInputs(client)
        return stopped || (pressed && running.isNotEmpty())
    }

    private fun handleRuntimeViewerKey(client: Minecraft) {
        val viewerKey = KeyboardUtils.normalizeKey(MacroStorage.config.runtimeViewerKey)
        if (!KeyboardUtils.isValidKeyName(viewerKey) || viewerKey == "NONE") return
        val pressed = KeyboardUtils.isInputPressed(viewerKey)
        if (pressed && !runtimeViewerWasPressed && client.screen == null) {
            client.setScreen(MacroRuntimeViewerScreen())
        }
        runtimeViewerWasPressed = pressed
    }

    private fun releaseHeldInputs(client: Minecraft) {
        RouteNavigator(client).releaseMovement()
        listOf("LCTRL", "RCTRL", "LALT", "RALT", "LSHIFT", "RSHIFT").forEach { KeyboardUtils.set(it, false) }
    }

    fun onChat(message: String) {
        ensureLoaded()
        fireEvent("onChat", mapOf("CHAT" to message, "CHATCLEAN" to ClientUtils.stripColors(message)))
    }

    private fun updateWorldState(client: Minecraft) {
        val present = client.level != null && client.player != null
        if (present && !lastWorldPresent) {
            fireEvent("onJoinGame")
            fireEvent("onWorldChange")
        } else if (!present && lastWorldPresent) {
            fireEvent("onWorldChange")
        }
        lastWorldPresent = present
    }
}
