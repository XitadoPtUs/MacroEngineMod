package github.xitadoptus.macro.gui

import com.mojang.blaze3d.platform.InputConstants
import github.xitadoptus.macro.engine.MacroEntry
import github.xitadoptus.macro.engine.MacroEventBinding
import github.xitadoptus.macro.engine.MacroRuntime
import github.xitadoptus.macro.engine.MacroStorage
import github.xitadoptus.macro.recorder.MacroRecorder
import github.xitadoptus.macro.recorder.builder.StepBuilderCaptureController
import github.xitadoptus.macro.util.ClientUtils
import github.xitadoptus.macro.util.KeyboardUtils
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Renderable
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.awt.Color

class MacroScreen(private val previousScreen: Screen?) : Screen(Component.literal("MacroEngine")) {
    private enum class Mode { MACROS, EVENTS }

    private var mode = Mode.MACROS
    private var selectedIndex = 0
    private var listScroll = 0
    private var nameField: EditBox? = null
    private var editor = TextEditor()
    private var waitingForBind = false
    private var waitingForRecorderStopBind = false
    private var waitingForRuntimeViewerBind = false
    private var bindButton: Button? = null
    private var recorderButton: Button? = null
    private var recorderStopButton: Button? = null
    private var runtimeViewerButton: Button? = null
    private var builderButton: Button? = null
    private var runButton: Button? = null
    private var deleteButton: Button? = null
    private var enabledButton: Button? = null
    private val renderedWidgets = mutableListOf<Renderable>()

    override fun init() {
        MacroRuntime.ensureLoaded()
        clearWidgets()
        renderedWidgets.clear()

        val left = 18
        val top = 24
        val right = width - 18
        val bottom = height - 18
        val listWidth = 170
        val buttonY = bottom - 22
        val builderY = buttonY - 24

        addButton(7, left, top - 4, 78, 20, "Macros")
        addButton(8, left + 84, top - 4, 78, 20, "Events")
        addButton(1, left, buttonY, 50, 20, "New")
        deleteButton = addButton(3, left + 56, buttonY, 50, 20, "Delete")
        enabledButton = addButton(6, left + 112, buttonY, 56, 20, "Enabled")
        addButton(2, left + listWidth + 16, buttonY, 62, 20, "Save")
        runButton = addButton(4, left + listWidth + 84, buttonY, 62, 20, "Run")
        bindButton = addButton(5, left + listWidth + 152, buttonY, 72, 20, "Bind")
        recorderButton = addButton(9, left + listWidth + 230, buttonY, 104, 20, "Start Recorder")
        recorderStopButton = addButton(10, left + listWidth + 340, buttonY, 104, 20, "Stop: ${MacroStorage.config.recorderStopKey}")
        builderButton = addButton(11, left + listWidth + 16, builderY, 124, 20, builderButtonText())
        runtimeViewerButton = addButton(13, left + listWidth + 146, builderY, 112, 20, "Viewer: ${MacroStorage.config.runtimeViewerKey}")
        addButton(0, right - 62, buttonY, 62, 20, "Back")

        nameField = EditBox(font, left + listWidth + 18, top + 46, right - left - listWidth - 36, 18, Component.literal("")).also {
            it.setMaxLength(64)
            it.addFormatter(EditBox.TextFormatter { value, _ -> MacroFonts.sequence(value) })
            addRenderableWidget(it)
            renderedWidgets += it
        }

        editor.setBounds(left + listWidth + 18, top + 76, right - left - listWidth - 36, bottom - top - 136)
        loadSelection()
        refreshButtons()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (!MacroScreenRenderPolicy.USE_VANILLA_BACKGROUND) {
            renderTransparentBackground(graphics)
        }

        val left = 18
        val top = 24
        val right = width - 18
        val bottom = height - 18
        val listWidth = 170

        graphics.fill(left, top + 22, left + listWidth, bottom - 28, Color(18, 20, 24, 210).rgb)
        graphics.fill(left + listWidth + 10, top + 22, right, bottom - 28, Color(18, 20, 24, 210).rgb)
        graphics.drawString(font, MacroFonts.text("Macro / Keybind"), left + listWidth + 18, top - 2, Color.WHITE.rgb, true)
        graphics.drawString(font, MacroFonts.text(if (mode == Mode.MACROS) "Key macros" else "Event scripts"), left + listWidth + 18, top + 13, Color(155, 165, 180).rgb, true)

        drawList(graphics, left, top + 26, listWidth, bottom - top - 58, mouseX, mouseY)

        val nameLabel = if (mode == Mode.MACROS) "Name" else "Event"
        graphics.drawString(font, MacroFonts.text(nameLabel), left + listWidth + 18, top + 34, Color(190, 195, 205).rgb, true)

        if (mode == Mode.MACROS) {
            val currentKey = currentMacro()?.key ?: "NONE"
            val keyText = when {
                waitingForBind -> "Press a supported key..."
                waitingForRecorderStopBind -> "Recorder stop: press a supported key..."
                waitingForRuntimeViewerBind -> "Runtime viewer: press a supported key..."
                else -> "Key: $currentKey"
            }
            val keyComponent = MacroFonts.text(keyText)
            val keyX = (right - font.width(keyComponent) - 12).coerceAtLeast(left + listWidth + 18)
            graphics.drawString(font, keyComponent, keyX, top + 13, Color(190, 195, 205).rgb, true)
        }

        editor.render(graphics, font)
        renderedWidgets.forEach { it.render(graphics, mouseX, mouseY, partialTick) }
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = mouseButtonEvent.x()
        val mouseY = mouseButtonEvent.y()
        val button = mouseButtonEvent.button()
        val left = 18
        val top = 24
        val bottom = height - 18
        val listWidth = 170
        val listY = top + 26
        val listH = bottom - top - 58

        if (mouseX.toInt() in left..(left + listWidth) && mouseY.toInt() in listY..(listY + listH)) {
            val index = listScroll + ((mouseY.toInt() - listY) / 22)
            if (index in itemLabels().indices) {
                saveSelection()
                selectedIndex = index
                loadSelection()
                refreshButtons()
                return true
            }
        }

        if (editor.mouseClicked(mouseX.toInt(), mouseY.toInt(), button, font)) return true
        return super.mouseClicked(mouseButtonEvent, doubleClick)
    }

    override fun mouseDragged(mouseButtonEvent: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val mouseX = mouseButtonEvent.x()
        val mouseY = mouseButtonEvent.y()
        val button = mouseButtonEvent.button()
        if (editor.mouseDragged(mouseX.toInt(), mouseY.toInt(), button, font)) return true
        return super.mouseDragged(mouseButtonEvent, dragX, dragY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val left = 18
        val top = 24
        val listWidth = 170
        val bottom = height - 18

        if (mouseX.toInt() in left..(left + listWidth) && mouseY.toInt() in (top + 26)..(bottom - 32)) {
            listScroll += if (verticalAmount < 0) 1 else -1
            return true
        }

        editor.mouseWheel(verticalAmount)
        return true
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        val keyCode = keyEvent.key()
        if (waitingForRuntimeViewerBind) {
            bindRuntimeViewerKey(keyCode)
            return true
        }

        if (waitingForRecorderStopBind) {
            bindRecorderStopKey(keyCode)
            return true
        }

        if (waitingForBind) {
            bindKey(keyCode)
            return true
        }

        if (keyCode == InputConstants.KEY_ESCAPE) {
            saveAndClose()
            return true
        }

        if (keyEvent.hasControlDown() && keyCode == InputConstants.KEY_S) {
            saveSelection()
            MacroRuntime.save()
            ClientUtils.displayChatMessage("\u00A7a[MacroEngine] Saved.")
            return true
        }

        if (nameField?.isFocused == true) return super.keyPressed(keyEvent)
        if (editor.keyPressed(keyCode, font, minecraft!!.keyboardHandler.clipboard)) return true
        return super.keyPressed(keyEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (nameField?.isFocused == true) return super.charTyped(characterEvent)
        val value = characterEvent.codepointAsString()
        if (value.length == 1 && editor.charTyped(value[0])) return true
        return super.charTyped(characterEvent)
    }

    override fun removed() {
        saveSelection()
        MacroRuntime.save()
    }

    override fun onClose() {
        saveAndClose()
    }

    override fun isPauseScreen(): Boolean = false

    private fun addButton(id: Int, x: Int, y: Int, w: Int, h: Int, text: String): Button {
        return addButton(id, x, y, w, h, MacroFonts.text(text))
    }

    private fun addButton(id: Int, x: Int, y: Int, w: Int, h: Int, text: Component): Button {
        val button = addRenderableWidget(
            Button.builder(text) {
                handleButton(id)
            }.bounds(x, y, w, h).build()
        )
        renderedWidgets += button
        return button
    }

    private fun handleButton(id: Int) {
        when (id) {
            0 -> saveAndClose()
            1 -> newEntry()
            2 -> {
                saveSelection()
                MacroRuntime.save()
                ClientUtils.displayChatMessage("\u00A7a[MacroEngine] Saved.")
            }
            3 -> deleteEntry()
            4 -> runCurrent()
            5 -> if (mode == Mode.MACROS) {
                waitingForBind = true
                waitingForRecorderStopBind = false
                waitingForRuntimeViewerBind = false
            }
            6 -> toggleEnabled()
            7 -> switchMode(Mode.MACROS)
            8 -> switchMode(Mode.EVENTS)
            9 -> startRecorder()
            10 -> {
                waitingForRecorderStopBind = true
                waitingForBind = false
                waitingForRuntimeViewerBind = false
            }
            11 -> startBuilder()
            13 -> {
                waitingForRuntimeViewerBind = true
                waitingForBind = false
                waitingForRecorderStopBind = false
            }
        }
        refreshButtons()
    }

    private fun drawList(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val items = itemLabels()
        val rowH = 22
        val visible = (h / rowH).coerceAtLeast(1)
        val maxScroll = (items.size - visible).coerceAtLeast(0)
        listScroll = listScroll.coerceIn(0, maxScroll)

        if (items.isEmpty()) {
            graphics.drawString(font, MacroFonts.text("No entries"), x + 10, y + 10, Color(145, 150, 160).rgb, true)
            return
        }

        for (i in 0 until visible) {
            val index = i + listScroll
            if (index !in items.indices) break
            val rowY = y + i * rowH
            val hovered = mouseX in x..(x + w) && mouseY in rowY..(rowY + rowH)
            val selected = index == selectedIndex
            val color = when {
                selected -> Color(55, 95, 145, 220).rgb
                hovered -> Color(35, 40, 48, 230).rgb
                else -> Color(24, 27, 32, 180).rgb
            }
            graphics.fill(x + 4, rowY + 2, x + w - 4, rowY + rowH - 2, color)

            val enabled = if (isEnabled(index)) "on" else "off"
            graphics.drawString(font, MacroFonts.text(trim(items[index], 19)), x + 10, rowY + 7, if (selected) Color.WHITE.rgb else Color(205, 210, 218).rgb, false)
            graphics.drawString(font, MacroFonts.text(enabled), x + w - 28, rowY + 7, if (isEnabled(index)) Color(92, 230, 145).rgb else Color(220, 105, 105).rgb, false)
        }
    }

    private fun switchMode(nextMode: Mode) {
        if (mode == nextMode) return
        saveSelection()
        mode = nextMode
        selectedIndex = 0
        listScroll = 0
        waitingForBind = false
        waitingForRecorderStopBind = false
        waitingForRuntimeViewerBind = false
        loadSelection()
    }

    private fun newEntry() {
        saveSelection()
        if (mode == Mode.MACROS) {
            MacroStorage.config.macros += MacroEntry(name = "Macro ${MacroStorage.config.macros.size + 1}", script = "$" + "$" + "{\nLOG(\"new macro\");\n}" + "$" + "$")
            selectedIndex = MacroStorage.config.macros.lastIndex
        } else {
            MacroStorage.config.events += MacroEventBinding(event = "onJoinGame", script = "$" + "$" + "{\nLOG(\"joined game\");\n}" + "$" + "$")
            selectedIndex = MacroStorage.config.events.lastIndex
        }
        loadSelection()
    }

    private fun deleteEntry() {
        if (mode == Mode.MACROS) {
            if (selectedIndex in MacroStorage.config.macros.indices) MacroStorage.config.macros.removeAt(selectedIndex)
            selectedIndex = selectedIndex.coerceAtMost(MacroStorage.config.macros.lastIndex.coerceAtLeast(0))
        } else {
            if (selectedIndex in MacroStorage.config.events.indices) MacroStorage.config.events.removeAt(selectedIndex)
            selectedIndex = selectedIndex.coerceAtMost(MacroStorage.config.events.lastIndex.coerceAtLeast(0))
        }
        loadSelection()
    }

    private fun toggleEnabled() {
        if (mode == Mode.MACROS) {
            currentMacro()?.let {
                it.enabled = !it.enabled
                if (!it.enabled && MacroRuntime.stopMatching(it.name)) {
                    ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Macro disabled and stopped.")
                }
            }
        } else {
            currentEvent()?.let { it.enabled = !it.enabled }
        }
    }

    private fun runCurrent() {
        saveSelection()
        if (mode == Mode.MACROS) {
            currentMacro()?.let {
                MacroRuntime.runMacro(it)
                if (it.builder != null) minecraft!!.setScreen(null)
            }
        } else {
            currentEvent()?.let { MacroRuntime.runScript(it.script, it.event) }
        }
    }

    private fun saveSelection() {
        if (mode == Mode.MACROS) {
            currentMacro()?.let {
                it.name = nameField?.value?.ifBlank { it.name } ?: it.name
                it.script = editor.text
            }
        } else {
            currentEvent()?.let {
                it.event = nameField?.value?.ifBlank { it.event } ?: it.event
                it.script = editor.text
            }
        }
    }

    private fun loadSelection() {
        val labels = itemLabels()
        if (labels.isEmpty()) {
            nameField?.value = ""
            editor.text = ""
            return
        }
        selectedIndex = selectedIndex.coerceIn(0, labels.lastIndex)

        if (mode == Mode.MACROS) {
            val macro = currentMacro()
            nameField?.value = macro?.name ?: ""
            editor.text = macro?.script ?: ""
        } else {
            val event = currentEvent()
            nameField?.value = event?.event ?: ""
            editor.text = event?.script ?: ""
        }
        editor.cursorEnd()
    }

    private fun bindKey(keyCode: Int) {
        waitingForBind = false
        val macro = currentMacro() ?: return
        if (keyCode == InputConstants.KEY_ESCAPE || keyCode == InputConstants.KEY_BACKSPACE) {
            macro.key = "NONE"
            return
        }

        val normalized = KeyboardUtils.normalizeKey(KeyboardUtils.keyNameFromCode(keyCode))
        macro.key = if (KeyboardUtils.isValidKeyName(normalized)) normalized else "NONE"
        if (macro.key == "NONE") {
            ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Unsupported key: \u00A7f$normalized")
        }
    }

    private fun bindRecorderStopKey(keyCode: Int) {
        waitingForRecorderStopBind = false
        if (keyCode == InputConstants.KEY_ESCAPE || keyCode == InputConstants.KEY_BACKSPACE) {
            MacroStorage.config.recorderStopKey = "RSHIFT"
            MacroRuntime.save()
            return
        }

        val normalized = KeyboardUtils.normalizeKey(KeyboardUtils.keyNameFromCode(keyCode))
        if (KeyboardUtils.isValidKeyName(normalized) && normalized != "NONE") {
            MacroStorage.config.recorderStopKey = normalized
            MacroRuntime.save()
        } else {
            ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Unsupported recorder stop key: \u00A7f$normalized")
        }
    }

    private fun bindRuntimeViewerKey(keyCode: Int) {
        waitingForRuntimeViewerBind = false
        if (keyCode == InputConstants.KEY_ESCAPE || keyCode == InputConstants.KEY_BACKSPACE) {
            MacroStorage.config.runtimeViewerKey = "V"
            MacroRuntime.save()
            return
        }

        val normalized = KeyboardUtils.normalizeKey(KeyboardUtils.keyNameFromCode(keyCode))
        if (KeyboardUtils.isValidKeyName(normalized) && normalized != "NONE") {
            MacroStorage.config.runtimeViewerKey = normalized
            MacroRuntime.save()
        } else {
            ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Unsupported runtime viewer key: \u00A7f$normalized")
        }
    }

    private fun startRecorder() {
        saveSelection()
        MacroRuntime.save()
        if (MacroRecorder.start(MacroStorage.config.recorderStopKey)) {
            minecraft!!.setScreen(null)
        }
    }

    private fun startBuilder() {
        saveSelection()
        MacroRuntime.save()
        val name = nameField?.value?.takeIf { it.isNotBlank() } ?: "Step Builder ${MacroStorage.config.macros.size + 1}"
        val command = editor.text.lineSequence().firstOrNull { it.trim().startsWith("/") }?.trim() ?: "/vip"
        if (StepBuilderCaptureController.start(name, command)) {
            minecraft!!.setScreen(null)
        }
    }

    private fun saveAndClose() {
        saveSelection()
        MacroRuntime.save()
        minecraft!!.setScreen(previousScreen)
    }

    private fun refreshButtons() {
        bindButton?.active = mode == Mode.MACROS && currentMacro() != null
        recorderButton?.active = !MacroRecorder.active
        builderButton?.active = mode == Mode.MACROS
        builderButton?.message = builderButtonText()
        recorderStopButton?.message = MacroFonts.text(if (waitingForRecorderStopBind) "Press key" else "Stop: ${MacroStorage.config.recorderStopKey}")
        runtimeViewerButton?.message = MacroFonts.text(if (waitingForRuntimeViewerBind) "Press key" else "Viewer: ${MacroStorage.config.runtimeViewerKey}")
        runButton?.active = if (mode == Mode.MACROS) currentMacro() != null else currentEvent() != null
        deleteButton?.active = itemLabels().isNotEmpty()
        enabledButton?.message = MacroFonts.text(if (currentEnabled()) "Enabled" else "Disabled")
    }

    private fun itemLabels(): List<String> {
        return if (mode == Mode.MACROS) MacroStorage.config.macros.map { it.name } else MacroStorage.config.events.map { it.event }
    }

    private fun isEnabled(index: Int): Boolean {
        return if (mode == Mode.MACROS) MacroStorage.config.macros.getOrNull(index)?.enabled == true else MacroStorage.config.events.getOrNull(index)?.enabled == true
    }

    private fun currentEnabled(): Boolean {
        return if (mode == Mode.MACROS) currentMacro()?.enabled == true else currentEvent()?.enabled == true
    }

    private fun currentMacro(): MacroEntry? = MacroStorage.config.macros.getOrNull(selectedIndex)
    private fun currentEvent(): MacroEventBinding? = MacroStorage.config.events.getOrNull(selectedIndex)

    private fun trim(text: String, max: Int): String {
        return if (text.length <= max) text else text.take(max - 3) + "..."
    }

    private fun builderButtonText(): Component {
        return Component.literal("")
            .append(MacroFonts.text("Start Builder "))
            .append(Component.literal("[BETA]").setStyle(MacroFonts.STYLE.withColor(ChatFormatting.RED)))
    }
}
