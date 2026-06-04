package github.xitadoptus.macro.gui

import github.xitadoptus.macro.engine.MacroRuntime
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.ChatScreen
import java.awt.Color

class MacroRuntimeViewerScreen : ChatScreen("") {
    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        MacroRuntimeViewerOverlay.render(graphics, font, mouseX, mouseY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && MacroRuntimeViewerOverlay.mouseClicked(mouseX.toInt(), mouseY.toInt())) return true
        return super.mouseClicked(mouseX, mouseY, button)
    }
}

object MacroRuntimeViewerOverlay {
    private data class StopHitbox(val macroName: String, val x: Int, val y: Int, val width: Int, val height: Int)

    private val hitboxes = mutableListOf<StopHitbox>()

    fun render(graphics: GuiGraphics, font: Font, mouseX: Int, mouseY: Int) {
        val names = MacroRuntime.runningNames()
        val x = 8
        val y = 8
        val rowHeight = 16
        val width = (names.maxOfOrNull { font.width(MacroFonts.text(it)) } ?: font.width(MacroFonts.text("No macros running")))
            .coerceAtLeast(font.width(MacroFonts.text("Running macros"))) + 44
        val height = 28 + names.size.coerceAtLeast(1) * rowHeight

        hitboxes.clear()
        graphics.fill(x, y, x + width, y + height, Color(10, 12, 16, 225).rgb)
        graphics.fill(x, y, x + width, y + 18, Color(28, 34, 42, 235).rgb)
        graphics.drawString(font, MacroFonts.text("Running macros"), x + 8, y + 5, Color.WHITE.rgb, false)

        if (names.isEmpty()) {
            graphics.drawString(font, MacroFonts.text("No macros running"), x + 8, y + 26, Color(155, 165, 180).rgb, false)
            return
        }

        names.forEachIndexed { index, name ->
            val rowY = y + 22 + index * rowHeight
            val stopX = x + width - 18
            val hovered = mouseX in stopX..(stopX + 11) && mouseY in rowY..(rowY + 11)
            graphics.drawString(font, MacroFonts.text(name), x + 8, rowY + 1, Color(218, 224, 232).rgb, false)
            graphics.fill(stopX, rowY, stopX + 11, rowY + 11, if (hovered) Color(190, 48, 48, 245).rgb else Color(125, 34, 42, 235).rgb)
            graphics.drawString(font, MacroFonts.text("X"), stopX + 3, rowY + 2, Color.WHITE.rgb, false)
            hitboxes += StopHitbox(name, stopX, rowY, 11, 11)
        }
    }

    fun mouseClicked(mouseX: Int, mouseY: Int): Boolean {
        val hit = hitboxes.firstOrNull { mouseX in it.x..(it.x + it.width) && mouseY in it.y..(it.y + it.height) } ?: return false
        MacroRuntime.stopMatching(hit.macroName)
        return true
    }
}
