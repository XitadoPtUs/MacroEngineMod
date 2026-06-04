package github.xitadoptus.macro.recorder.builder

import github.xitadoptus.macro.engine.MacroStorage
import github.xitadoptus.macro.gui.MacroFonts
import github.xitadoptus.macro.util.ClientUtils
import github.xitadoptus.macro.util.KeyboardUtils
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.phys.BlockHitResult
import java.awt.Color

object StepBuilderCaptureController {
    private const val MARK_KEY = "B"
    private const val TARGET_KEY = "N"
    private val recorder = StepBuilderRecorder()

    @Volatile
    private var active = false

    private var markWasPressed = false
    private var targetWasPressed = false
    private var stopWasPressed = false
    private var status = ""

    fun start(name: String, command: String): Boolean {
        val client = Minecraft.getInstance()
        if (client.level == null || client.player == null) {
            ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Join a world before starting step builder.")
            return false
        }

        recorder.start(name)
        recorder.setCommand(command)
        active = true
        markWasPressed = false
        targetWasPressed = false
        stopWasPressed = KeyboardUtils.isInputPressed(MacroStorage.config.recorderStopKey)
        status = "Step builder: B mark, N target, ${MacroStorage.config.recorderStopKey} finish"
        ClientUtils.displayChatMessage("\u00A7a[MacroEngine] Step builder started.")
        return true
    }

    fun onClientTick(client: Minecraft): Boolean {
        if (!active) return false
        if (client.level == null || client.player == null) {
            active = false
            ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Step builder cancelled: world closed.")
            return true
        }

        recordAutomaticWaypoint(client)
        handleHotkeys(client)
        return true
    }

    fun renderOverlay(graphics: GuiGraphics) {
        if (!active) return
        val client = Minecraft.getInstance()
        val font = client.font
        val width = client.window.guiScaledWidth
        val x = (width - font.width(status) - 18).coerceAtLeast(10)
        val y = 40
        graphics.fill(x - 8, y - 6, width - 10, y + 16, Color(15, 18, 22, 220).rgb)
        graphics.drawString(font, MacroFonts.text(status), x, y, Color.WHITE.rgb, true)
    }

    fun renderWorld(context: WorldRenderContext) {
        if (!active) return
        StepBuilderWorldPreview.render(context, recorder.previewWaypoints(), recorder.previewTarget())
    }

    private fun handleHotkeys(client: Minecraft) {
        val markPressed = KeyboardUtils.isInputPressed(MARK_KEY)
        val targetPressed = KeyboardUtils.isInputPressed(TARGET_KEY)
        val stopPressed = KeyboardUtils.isInputPressed(MacroStorage.config.recorderStopKey)

        if (markPressed && !markWasPressed) markManualWaypoint(client)
        if (targetPressed && !targetWasPressed) setTarget(client)
        if (stopPressed && !stopWasPressed) finish()

        markWasPressed = markPressed
        targetWasPressed = targetPressed
        stopWasPressed = stopPressed
    }

    private fun recordAutomaticWaypoint(client: Minecraft) {
        val player = client.player ?: return
        recorder.recordAutomaticWaypoint(
            RouteWaypoint(
                x = player.x,
                y = player.y,
                z = player.z,
                yaw = player.yRot,
                pitch = player.xRot,
                manual = false
            )
        )
    }

    private fun markManualWaypoint(client: Minecraft) {
        val player = client.player ?: return
        recorder.addWaypoint(
            RouteWaypoint(
                x = player.x,
                y = player.y,
                z = player.z,
                yaw = player.yRot,
                pitch = player.xRot,
                manual = true
            )
        )
        ClientUtils.displayChatMessage("\u00A7a[MacroEngine] Waypoint marked.")
    }

    private fun setTarget(client: Minecraft) {
        val hit = client.hitResult as? BlockHitResult
        if (hit == null) {
            ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Aim at a block first.")
            return
        }
        val pos = hit.blockPos
        recorder.setSellTarget(BlockTarget(pos.x, pos.y, pos.z), SellAction())
        ClientUtils.displayChatMessage("\u00A7a[MacroEngine] Sell target saved.")
    }

    private fun finish() {
        val macro = recorder.finish()
        MacroStorage.config.macros += macro
        MacroStorage.save()
        active = false
        ClientUtils.displayChatMessage("\u00A7a[MacroEngine] Step builder macro created: \u00A7f${macro.name}")
    }
}
