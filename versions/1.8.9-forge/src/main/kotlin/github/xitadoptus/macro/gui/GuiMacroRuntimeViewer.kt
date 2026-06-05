package github.xitadoptus.macro.gui

import github.xitadoptus.macro.engine.MacroRuntime
import github.xitadoptus.macro.util.MinecraftInstance
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.ScaledResolution
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.client.event.MouseEvent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import org.lwjgl.input.Mouse
import java.awt.Color

object GuiMacroRuntimeViewer : MinecraftInstance() {
    private data class StopHitbox(val macroName: String, val x: Int, val y: Int, val width: Int, val height: Int)

    private val hitboxes = mutableListOf<StopHitbox>()

    @Volatile
    private var active = false

    fun isActive(): Boolean = active

    fun toggle() {
        if (active) close() else open()
    }

    fun onClientTick() {
        if (!active || mc.currentScreen != null) return
        mc.setIngameNotInFocus()
        Mouse.setGrabbed(false)
    }

    private fun open() {
        active = true
        if (mc.currentScreen == null) {
            mc.setIngameNotInFocus()
            Mouse.setGrabbed(false)
        }
    }

    private fun close() {
        active = false
        hitboxes.clear()
        if (mc.currentScreen == null) mc.setIngameFocus()
    }

    @SubscribeEvent
    fun onRenderGameOverlay(event: RenderGameOverlayEvent.Post) {
        if (!active || mc.currentScreen != null || event.type != RenderGameOverlayEvent.ElementType.ALL) return
        val mouse = scaledMouse()
        render(mouse.first, mouse.second)
    }

    @SubscribeEvent
    fun onDrawScreen(event: GuiScreenEvent.DrawScreenEvent.Post) {
        if (!active) return
        render(event.mouseX, event.mouseY)
    }

    @SubscribeEvent
    fun onMouse(event: MouseEvent) {
        if (!active || mc.currentScreen != null || event.button < 0) return
        if (event.button == 0 && event.buttonstate) {
            val mouse = scaledMouse()
            mouseClicked(mouse.first, mouse.second)
        }
        event.setCanceled(true)
    }

    @SubscribeEvent
    fun onGuiMouseInput(event: GuiScreenEvent.MouseInputEvent.Pre) {
        if (!active || mc.currentScreen == null) return
        val button = Mouse.getEventButton()
        if (button != 0 || !Mouse.getEventButtonState()) return
        val mouse = scaledMouse()
        if (mouseClicked(mouse.first, mouse.second)) event.setCanceled(true)
    }

    private fun render(mouseX: Int, mouseY: Int) {
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
        Gui.drawRect(x, y, x + width, y + height, Color(10, 12, 16, 225).rgb)
        Gui.drawRect(x, y, x + width, y + 18, Color(28, 34, 42, 235).rgb)
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
            Gui.drawRect(stopX, rowY, stopX + 11, rowY + 11, if (hovered) Color(190, 48, 48, 245).rgb else Color(125, 34, 42, 235).rgb)
            mc.fontRendererObj.drawString("X", stopX + 3, rowY + 2, Color.WHITE.rgb)
            hitboxes += StopHitbox(name, stopX, rowY, 11, 11)
        }
    }

    private fun mouseClicked(mouseX: Int, mouseY: Int): Boolean {
        if (!active) return false
        val hit = hitboxes.firstOrNull { mouseX in it.x..(it.x + it.width) && mouseY in it.y..(it.y + it.height) } ?: return false
        MacroRuntime.stopMatching(hit.macroName)
        return true
    }

    private fun scaledMouse(): Pair<Int, Int> {
        val scaled = ScaledResolution(mc)
        val x = Mouse.getX() * scaled.scaledWidth / mc.displayWidth
        val y = scaled.scaledHeight - Mouse.getY() * scaled.scaledHeight / mc.displayHeight - 1
        return x to y
    }
}
