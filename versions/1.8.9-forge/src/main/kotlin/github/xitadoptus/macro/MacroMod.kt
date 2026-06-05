package github.xitadoptus.macro

import github.xitadoptus.macro.engine.MacroRuntime
import github.xitadoptus.macro.gui.GuiMacroEngine
import github.xitadoptus.macro.gui.GuiMacroRuntimeViewer
import github.xitadoptus.macro.update.UpdateChecker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiChat
import net.minecraftforge.client.ClientCommandHandler
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.FMLCommonHandler
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.event.FMLInitializationEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.io.File

@Mod(
    modid = MacroMod.MOD_ID,
    name = MacroMod.NAME,
    version = MacroMod.VERSION,
    acceptedMinecraftVersions = "[1.8,1.8.9]",
    acceptableRemoteVersions = "*",
    clientSideOnly = true
)
class MacroMod {
    @Mod.EventHandler
    @Suppress("UNUSED_PARAMETER")
    fun init(event: FMLInitializationEvent) {
        if (!configDir.exists()) configDir.mkdirs()
        MacroRuntime.ensureLoaded()
        MinecraftForge.EVENT_BUS.register(MacroRuntime)
        MinecraftForge.EVENT_BUS.register(GuiMacroRuntimeViewer)
        FMLCommonHandler.instance().bus().register(MacroRuntime)
        FMLCommonHandler.instance().bus().register(this)
        ClientCommandHandler.instance.registerCommand(MacroCommand())
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getMinecraft()
        UpdateChecker.onClientTick(mc)

        if (!pendingGuiOpen) return
        if (mc.currentScreen is GuiChat) return

        pendingGuiOpen = false
        if (mc.currentScreen !is GuiMacroEngine) {
            mc.displayGuiScreen(GuiMacroEngine(mc.currentScreen))
        }
    }

    companion object {
        const val MOD_ID = "macroengine"
        const val NAME = "MacroEngine"
        const val VERSION = "1.0.0"

        @Volatile
        private var pendingGuiOpen = false

        val configDir: File
            get() = File(Minecraft.getMinecraft().mcDataDir, "macroengine")

        fun openGui() {
            pendingGuiOpen = true
        }
    }
}
