package github.xitadoptus.macro.gui

import github.xitadoptus.macro.engine.MacroRuntime
import net.minecraft.client.gui.GuiChat
import java.awt.Color
import java.io.IOException

class GuiMacroRuntimeViewer : GuiChat("") {
    private data class StopHitbox(val macroName: String, val x: Int, val y: Int, val width: Int, val height: Int)

    private val hitboxes = mutableListOf<StopHitbox>()

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.drawScreen(mouseX, mouseY, partialTicks)
        drawRuntimeViewer(mouseX, mouseY)
    }

    @Throws(IOException::class)
    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (mouseButton == 0 && clickRuntimeViewer(mouseX, mouseY)) return
        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    private fun drawRuntimeViewer(mouseX: Int, mouseY: Int) {
        val names = MacroRuntime.runningNames()
        val x = 8
        val y = 8
        val rowHeight = 16
        val titleWidth = mc.fontRendererObj.getStringWidth("Running macros")
        val emptyWidth = mc.fontRendererObj.getStringWidth("No macros running")
        val nameWidth = names.map { mc.fontRendererObj.getStringWidth(it) }.max() ?: emptyWidth
        val width = titleWidth.coerceAtLeast(nameWidth) + 44
        val height = 28 + names.size.coerceAtLeast(1) * rowHeight

        hitboxes.clear()
        drawRect(x, y, x + width, y + height, Color(10, 12, 16, 225).rgb)
        drawRect(x, y, x + width, y + 18, Color(28, 34, 42, 235).rgb)
        mc.fontRendererObj.drawString("Running macros", x + 8, y + 5, Color.WHITE.rgb)

        if (names.isEmpty()) {
            mc.fontRendererObj.drawString("No macros running", x + 8, y + 26, Color(155, 165, 180).rgb)
            return
        }

        names.forEachIndexed { index, name ->
            val rowY = y + 22 + index * rowHeight
            val stopX = x + width - 18
            val hovered = mouseX in stopX..(stopX + 11) && mouseY in rowY..(rowY + 11)
            mc.fontRendererObj.drawString(name, x + 8, rowY + 1, Color(218, 224, 232).rgb)
            drawRect(stopX, rowY, stopX + 11, rowY + 11, if (hovered) Color(190, 48, 48, 245).rgb else Color(125, 34, 42, 235).rgb)
            mc.fontRendererObj.drawString("X", stopX + 3, rowY + 2, Color.WHITE.rgb)
            hitboxes += StopHitbox(name, stopX, rowY, 11, 11)
        }
    }

    private fun clickRuntimeViewer(mouseX: Int, mouseY: Int): Boolean {
        val hit = hitboxes.firstOrNull { mouseX in it.x..(it.x + it.width) && mouseY in it.y..(it.y + it.height) } ?: return false
        MacroRuntime.stopMatching(hit.macroName)
        return true
    }
}
