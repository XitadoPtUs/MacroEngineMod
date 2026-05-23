package github.xitadoptus.macro.engine

import github.xitadoptus.macro.util.ClientUtils
import github.xitadoptus.macro.util.KeyboardUtils
import github.xitadoptus.macro.util.MinecraftInstance
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
    private var loaded = false
    private var lastWorldPresent = false

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

        executor.execute {
            try {
                MacroScriptEngine(id, running, locals).execute(script)
            } catch (t: Throwable) {
                ClientUtils.displayChatMessage("§c[MacroEngine] $reason failed: ${t.message ?: t.javaClass.simpleName}")
                ClientUtils.logError("[MacroEngine] Macro '$reason' failed", t)
            } finally {
                running -= id
            }
        }
    }

    fun isRunning(name: String): Boolean {
        return running.any { it.startsWith(name, ignoreCase = true) }
    }

    fun runningIds(): List<String> {
        return running.toList()
    }

    fun stopMatching(name: String) {
        if (name.isBlank()) return
        running.removeIf { it.startsWith(name, ignoreCase = true) || it.contains(name, ignoreCase = true) }
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
            return
        }
        if (mc.currentScreen != null) return

        MacroStorage.config.macros
            .filter { it.enabled && it.key != "NONE" }
            .forEach { macro ->
                val key = KeyboardUtils.normalizeKey(macro.key)
                val pressed = KeyboardUtils.isKeyPressed(key)
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

    @SubscribeEvent
    fun onChat(event: ClientChatReceivedEvent) {
        ensureLoaded()
        val message = event.message?.unformattedText ?: return
        fireEvent("onChat", mapOf("CHAT" to message, "CHATCLEAN" to message))
    }

    private fun updateWorldState() {
        val present = mc.theWorld != null && mc.thePlayer != null
        if (present && !lastWorldPresent) {
            fireEvent("onJoinGame")
            fireEvent("onWorldChange")
        } else if (!present && lastWorldPresent) {
            fireEvent("onWorldChange")
        }
        lastWorldPresent = present
    }
}
