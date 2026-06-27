package github.xitadoptus.macro.update

import github.xitadoptus.macro.MacroMod
import github.xitadoptus.macro.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiMainMenu
import net.minecraft.client.gui.GuiScreen
import java.awt.Color
import java.awt.Desktop
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

data class ReleaseUpdate(val tag: String, val url: String)

object UpdateChecker {
    private const val API_URL = "https://api.github.com/repos/XitadoPtUs/MacroEngineMod/releases/latest"
    private const val RELEASES_URL = "https://github.com/XitadoPtUs/MacroEngineMod/releases"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MacroEngine-UpdateChecker").apply { isDaemon = true }
    }

    @Volatile
    private var started = false

    @Volatile
    private var shown = false

    @Volatile
    private var pending: ReleaseUpdate? = null

    fun onClientTick(mc: Minecraft) {
        if (!started && mc.currentScreen is GuiMainMenu) {
            started = true
            executor.execute {
                pending = fetchUpdate()
            }
        }

        val update = pending ?: return
        if (!shown && mc.currentScreen is GuiMainMenu) {
            pending = null
            shown = true
            mc.displayGuiScreen(GuiUpdatePrompt(mc.currentScreen, update))
        }
    }

    private fun fetchUpdate(): ReleaseUpdate? {
        return runCatching {
            val connection = URI(API_URL).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "${MacroMod.MOD_ID}/${MacroMod.VERSION}")
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { parseRelease(it.readText()) }
            } finally {
                connection.disconnect()
            }
        }.onFailure {
            ClientUtils.logError("[MacroEngine] Update check failed", it)
        }.getOrNull()
    }

    private fun parseRelease(json: String): ReleaseUpdate? {
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        if (!UpdateVersionPolicy.shouldNotify(MacroMod.VERSION, tag)) return null
        val url = Regex("\"html_url\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: RELEASES_URL
        return ReleaseUpdate(tag, url.replace("\\/", "/"))
    }
}

class GuiUpdatePrompt(private val previous: GuiScreen?, private val update: ReleaseUpdate) : GuiScreen() {
    override fun initGui() {
        val buttonY = height / 2 + 36
        buttonList.add(GuiButton(1, width / 2 - 106, buttonY, 100, 20, "Download"))
        buttonList.add(GuiButton(2, width / 2 + 6, buttonY, 100, 20, "Ignore"))
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        drawRect(0, 0, width, height, Color(0, 0, 0, 120).rgb)
        val boxWidth = 330
        val boxHeight = 118
        val x = width / 2 - boxWidth / 2
        val y = height / 2 - boxHeight / 2
        drawRect(x, y, x + boxWidth, y + boxHeight, Color(14, 16, 20, 240).rgb)
        drawRect(x, y, x + boxWidth, y + 20, Color(32, 38, 48, 245).rgb)
        drawCenteredString(fontRendererObj, "New update detected", width / 2, y + 8, Color.WHITE.rgb)
        drawCenteredString(fontRendererObj, "Download the latest release.", width / 2, y + 42, Color(205, 212, 224).rgb)
        drawCenteredString(fontRendererObj, update.tag, width / 2, y + 58, Color(145, 185, 255).rgb)
        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    @Throws(IOException::class)
    override fun actionPerformed(button: GuiButton) {
        when (button.id) {
            1 -> {
                openDownload()
                mc.displayGuiScreen(previous)
            }
            2 -> mc.displayGuiScreen(previous)
        }
    }

    override fun onGuiClosed() {
        if (mc.currentScreen == this) mc.displayGuiScreen(previous)
    }

    override fun doesGuiPauseGame(): Boolean = false

    private fun openDownload() {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(update.url))
            }
        }.onFailure {
            ClientUtils.logError("[MacroEngine] Failed to open update download", it)
        }
    }
}
