package github.xitadoptus.macro

import github.xitadoptus.macro.engine.MacroRuntime
import github.xitadoptus.macro.gui.MacroScreen
import github.xitadoptus.macro.recorder.MacroRecorder
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.resources.ResourceLocation
import java.io.File

object MacroMod : ClientModInitializer {
    const val MOD_ID = "macroengine"
    const val NAME = "MacroEngine"
    const val VERSION = "1.0.0"

    @Volatile
    private var pendingGuiOpen = false

    val configDir: File
        get() = File(Minecraft.getInstance().gameDirectory, "macroengine")

    override fun onInitializeClient() {
        if (!configDir.exists()) configDir.mkdirs()
        MacroRuntime.ensureLoaded()
        MacroCommand.register()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            MacroRuntime.onClientTick(client)
            openPendingGui(client)
        }

        HudLayerRegistrationCallback.EVENT.register(HudLayerRegistrationCallback { layers ->
            layers.attachLayerAfter(IdentifiedLayer.SUBTITLES, ResourceLocation.fromNamespaceAndPath(MOD_ID, "recorder_overlay")) { graphics, _ ->
                MacroRecorder.renderOverlay(graphics)
            }
        })

        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            MacroRuntime.onChat(message.string)
        }

        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay) MacroRuntime.onChat(message.string)
        }
    }

    fun openGui() {
        pendingGuiOpen = true
    }

    private fun openPendingGui(client: Minecraft) {
        if (!pendingGuiOpen) return
        if (client.screen is ChatScreen) return
        pendingGuiOpen = false
        if (client.screen !is MacroScreen) {
            client.setScreen(MacroScreen(client.screen))
        }
    }
}
