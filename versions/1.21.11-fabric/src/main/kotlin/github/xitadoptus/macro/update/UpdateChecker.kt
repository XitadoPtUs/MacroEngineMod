package github.xitadoptus.macro.update

import github.xitadoptus.macro.MacroMod
import github.xitadoptus.macro.gui.MacroFonts
import github.xitadoptus.macro.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import java.awt.Color
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

    fun onClientTick(client: Minecraft) {
        if (!started && client.screen is TitleScreen) {
            started = true
            executor.execute {
                pending = fetchUpdate()
            }
        }

        val update = pending ?: return
        if (!shown && client.screen is TitleScreen) {
            pending = null
            shown = true
            client.setScreen(UpdatePromptScreen(client.screen, update))
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

class UpdatePromptScreen(private val previous: Screen?, private val update: ReleaseUpdate) : Screen(MacroFonts.text("MacroEngine Update")) {
    override fun init() {
        val buttonY = height / 2 + 36
        addRenderableWidget(
            Button.builder(MacroFonts.text("Download")) {
                openDownload()
                minecraft?.setScreen(previous)
            }.bounds(width / 2 - 106, buttonY, 100, 20).build()
        )
        addRenderableWidget(
            Button.builder(MacroFonts.text("Ignore")) {
                minecraft?.setScreen(previous)
            }.bounds(width / 2 + 6, buttonY, 100, 20).build()
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.fill(0, 0, width, height, Color(0, 0, 0, 120).rgb)
        val boxWidth = 330
        val boxHeight = 118
        val x = width / 2 - boxWidth / 2
        val y = height / 2 - boxHeight / 2
        graphics.fill(x, y, x + boxWidth, y + boxHeight, Color(14, 16, 20, 240).rgb)
        graphics.fill(x, y, x + boxWidth, y + 20, Color(32, 38, 48, 245).rgb)
        graphics.drawCenteredString(font, MacroFonts.text("New update detected"), width / 2, y + 8, Color.WHITE.rgb)
        graphics.drawCenteredString(font, MacroFonts.text("Download the latest release."), width / 2, y + 42, Color(205, 212, 224).rgb)
        graphics.drawCenteredString(font, MacroFonts.text(update.tag), width / 2, y + 58, Color(145, 185, 255).rgb)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun onClose() {
        minecraft?.setScreen(previous)
    }

    private fun openDownload() {
        runCatching {
            Util.getPlatform().openUri(URI(update.url))
        }.onFailure {
            ClientUtils.logError("[MacroEngine] Failed to open update download", it)
        }
    }
}
