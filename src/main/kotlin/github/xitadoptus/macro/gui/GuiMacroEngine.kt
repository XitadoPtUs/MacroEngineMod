package github.xitadoptus.macro.gui

import github.xitadoptus.macro.engine.MacroEntry
import github.xitadoptus.macro.engine.MacroEventBinding
import github.xitadoptus.macro.engine.MacroRuntime
import github.xitadoptus.macro.engine.MacroStorage
import github.xitadoptus.macro.util.ClientUtils
import github.xitadoptus.macro.util.KeyboardUtils
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiTextField
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import java.awt.Color
import java.io.IOException

class GuiMacroEngine(private val previousScreen: GuiScreen?) : GuiScreen() {
    private enum class Mode { MACROS, EVENTS }

    private var mode = Mode.MACROS
    private var selectedIndex = 0
    private var listScroll = 0
    private var nameField: GuiTextField? = null
    private var editor = TextArea()
    private var waitingForBind = false

    override fun initGui() {
        MacroRuntime.ensureLoaded()
        buttonList.clear()

        val left = 18
        val top = 24
        val right = width - 18
        val bottom = height - 18
        val listWidth = 170
        val buttonY = bottom - 22

        buttonList.add(GuiButton(7, left, top - 4, 78, 20, "Macros"))
        buttonList.add(GuiButton(8, left + 84, top - 4, 78, 20, "Events"))
        buttonList.add(GuiButton(1, left, buttonY, 50, 20, "New"))
        buttonList.add(GuiButton(3, left + 56, buttonY, 50, 20, "Delete"))
        buttonList.add(GuiButton(6, left + 112, buttonY, 56, 20, "Enabled"))

        buttonList.add(GuiButton(2, left + listWidth + 16, buttonY, 62, 20, "Save"))
        buttonList.add(GuiButton(4, left + listWidth + 84, buttonY, 62, 20, "Run"))
        buttonList.add(GuiButton(5, left + listWidth + 152, buttonY, 72, 20, "Bind"))
        buttonList.add(GuiButton(0, right - 62, buttonY, 62, 20, "Back"))

        nameField = GuiTextField(0, mc.fontRendererObj, left + listWidth + 18, top + 46, right - left - listWidth - 36, 18)
        nameField?.maxStringLength = 64

        editor.setBounds(left + listWidth + 18, top + 76, right - left - listWidth - 36, bottom - top - 112)
        loadSelection()
        refreshButtons()
    }

    override fun updateScreen() {
        nameField?.updateCursorCounter()
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        drawDefaultBackground()

        val left = 18
        val top = 24
        val right = width - 18
        val bottom = height - 18
        val listWidth = 170

        drawRect(left, top + 22, left + listWidth, bottom - 28, Color(18, 20, 24, 210).rgb)
        drawRect(left + listWidth + 10, top + 22, right, bottom - 28, Color(18, 20, 24, 210).rgb)

        mc.fontRendererObj.drawStringWithShadow("Macro / Keybind", (left + listWidth + 18).toFloat(), (top - 2).toFloat(), Color.WHITE.rgb)
        mc.fontRendererObj.drawStringWithShadow(
            if (mode == Mode.MACROS) "Key macros" else "Event scripts",
            (left + listWidth + 18).toFloat(),
            (top + 13).toFloat(),
            Color(155, 165, 180).rgb
        )

        drawList(left, top + 26, listWidth, bottom - top - 58, mouseX, mouseY)

        val nameLabel = if (mode == Mode.MACROS) "Name" else "Event"
        mc.fontRendererObj.drawStringWithShadow(nameLabel, (left + listWidth + 18).toFloat(), (top + 34).toFloat(), Color(190, 195, 205).rgb)
        nameField?.drawTextBox()

        if (mode == Mode.MACROS) {
            val currentKey = currentMacro()?.key ?: "NONE"
            val keyText = if (waitingForBind) "Press a supported key..." else "Key: $currentKey"
            mc.fontRendererObj.drawStringWithShadow(keyText, (right - 190).toFloat(), (top + 13).toFloat(), Color(190, 195, 205).rgb)
        }

        editor.draw()

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    private fun drawList(x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val items = itemLabels()
        val rowH = 22
        val visible = (h / rowH).coerceAtLeast(1)
        val maxScroll = (items.size - visible).coerceAtLeast(0)
        listScroll = listScroll.coerceIn(0, maxScroll)

        if (items.isEmpty()) {
            mc.fontRendererObj.drawStringWithShadow("No entries", (x + 10).toFloat(), (y + 10).toFloat(), Color(145, 150, 160).rgb)
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
            drawRect(x + 4, rowY + 2, x + w - 4, rowY + rowH - 2, color)

            val enabled = if (isEnabled(index)) "on" else "off"
            mc.fontRendererObj.drawString(
                trim(items[index], 19),
                x + 10,
                rowY + 7,
                if (selected) Color.WHITE.rgb else Color(205, 210, 218).rgb
            )
            mc.fontRendererObj.drawString(enabled, x + w - 28, rowY + 7, if (isEnabled(index)) Color(92, 230, 145).rgb else Color(220, 105, 105).rgb)
        }
    }

    @Throws(IOException::class)
    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        val left = 18
        val top = 24
        val bottom = height - 18
        val listWidth = 170

        val listY = top + 26
        val listH = bottom - top - 58
        if (mouseX in left..(left + listWidth) && mouseY in listY..(listY + listH)) {
            val index = listScroll + ((mouseY - listY) / 22)
            if (index in itemLabels().indices) {
                saveSelection()
                selectedIndex = index
                loadSelection()
                refreshButtons()
                return
            }
        }

        nameField?.mouseClicked(mouseX, mouseY, mouseButton)
        editor.mouseClicked(mouseX, mouseY, mouseButton)
        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    @Throws(IOException::class)
    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (waitingForBind) {
            bindKey(keyCode)
            return
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            saveAndClose()
            return
        }

        if (isCtrlKeyDown() && keyCode == Keyboard.KEY_S) {
            saveSelection()
            MacroRuntime.save()
            ClientUtils.displayChatMessage("§a[MacroEngine] Saved.")
            return
        }

        if (nameField?.isFocused == true) {
            nameField?.textboxKeyTyped(typedChar, keyCode)
            return
        }

        if (editor.keyTyped(typedChar, keyCode)) return
        super.keyTyped(typedChar, keyCode)
    }

    @Throws(IOException::class)
    override fun handleMouseInput() {
        super.handleMouseInput()
        val wheel = Mouse.getEventDWheel()
        if (wheel == 0) return

        val mx = Mouse.getEventX() * width / mc.displayWidth
        val my = height - Mouse.getEventY() * height / mc.displayHeight - 1
        val left = 18
        val top = 24
        val listWidth = 170
        val bottom = height - 18

        if (mx in left..(left + listWidth) && my in (top + 26)..(bottom - 32)) {
            listScroll += if (wheel < 0) 1 else -1
        } else {
            editor.mouseWheel(wheel)
        }
    }

    override fun actionPerformed(button: GuiButton) {
        when (button.id) {
            0 -> saveAndClose()
            1 -> newEntry()
            2 -> {
                saveSelection()
                MacroRuntime.save()
                ClientUtils.displayChatMessage("§a[MacroEngine] Saved.")
            }
            3 -> deleteEntry()
            4 -> runCurrent()
            5 -> if (mode == Mode.MACROS) waitingForBind = true
            6 -> toggleEnabled()
            7 -> switchMode(Mode.MACROS)
            8 -> switchMode(Mode.EVENTS)
        }
        refreshButtons()
    }

    override fun onGuiClosed() {
        saveSelection()
        MacroRuntime.save()
    }

    override fun doesGuiPauseGame(): Boolean = false

    private fun switchMode(nextMode: Mode) {
        if (mode == nextMode) return
        saveSelection()
        mode = nextMode
        selectedIndex = 0
        listScroll = 0
        waitingForBind = false
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
            currentMacro()?.let { it.enabled = !it.enabled }
        } else {
            currentEvent()?.let { it.enabled = !it.enabled }
        }
    }

    private fun runCurrent() {
        saveSelection()
        if (mode == Mode.MACROS) {
            currentMacro()?.let { MacroRuntime.runMacro(it) }
        } else {
            currentEvent()?.let { MacroRuntime.runScript(it.script, it.event) }
        }
    }

    private fun saveSelection() {
        if (mode == Mode.MACROS) {
            currentMacro()?.let {
                it.name = nameField?.text?.ifBlank { it.name } ?: it.name
                it.script = editor.text
            }
        } else {
            currentEvent()?.let {
                it.event = nameField?.text?.ifBlank { it.event } ?: it.event
                it.script = editor.text
            }
        }
    }

    private fun loadSelection() {
        val labels = itemLabels()
        if (labels.isEmpty()) {
            nameField?.text = ""
            editor.text = ""
            return
        }
        selectedIndex = selectedIndex.coerceIn(0, labels.lastIndex)

        if (mode == Mode.MACROS) {
            val macro = currentMacro()
            nameField?.text = macro?.name ?: ""
            editor.text = macro?.script ?: ""
        } else {
            val event = currentEvent()
            nameField?.text = event?.event ?: ""
            editor.text = event?.script ?: ""
        }
        editor.cursorEnd()
    }

    private fun bindKey(keyCode: Int) {
        waitingForBind = false
        val macro = currentMacro() ?: return
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_BACK) {
            macro.key = "NONE"
            return
        }

        val raw = Keyboard.getKeyName(keyCode) ?: "NONE"
        val normalized = KeyboardUtils.normalizeKey(raw)
        macro.key = if (KeyboardUtils.isValidKeyName(normalized)) normalized else "NONE"

        if (macro.key == "NONE") {
            ClientUtils.displayChatMessage("§c[MacroEngine] Unsupported key: §f$raw")
        }
    }

    private fun saveAndClose() {
        saveSelection()
        MacroRuntime.save()
        mc.displayGuiScreen(previousScreen)
    }

    private fun refreshButtons() {
        buttonList.firstOrNull { it.id == 5 }?.enabled = mode == Mode.MACROS && currentMacro() != null
        buttonList.firstOrNull { it.id == 4 }?.enabled = if (mode == Mode.MACROS) currentMacro() != null else currentEvent() != null
        buttonList.firstOrNull { it.id == 3 }?.enabled = itemLabels().isNotEmpty()
        buttonList.firstOrNull { it.id == 6 }?.displayString = if (currentEnabled()) "Enabled" else "Disabled"
    }

    private fun itemLabels(): List<String> {
        return if (mode == Mode.MACROS) {
            MacroStorage.config.macros.map { it.name }
        } else {
            MacroStorage.config.events.map { it.event }
        }
    }

    private fun isEnabled(index: Int): Boolean {
        return if (mode == Mode.MACROS) {
            MacroStorage.config.macros.getOrNull(index)?.enabled == true
        } else {
            MacroStorage.config.events.getOrNull(index)?.enabled == true
        }
    }

    private fun currentEnabled(): Boolean {
        return if (mode == Mode.MACROS) currentMacro()?.enabled == true else currentEvent()?.enabled == true
    }

    private fun currentMacro(): MacroEntry? = MacroStorage.config.macros.getOrNull(selectedIndex)
    private fun currentEvent(): MacroEventBinding? = MacroStorage.config.events.getOrNull(selectedIndex)

    private fun trim(text: String, max: Int): String {
        return if (text.length <= max) text else text.take(max - 3) + "..."
    }

    private inner class TextArea {
        var text: String = ""
        private var focused = false
        private var cursor = 0
        private var scrollLine = 0
        private var x = 0
        private var y = 0
        private var w = 0
        private var h = 0

        fun setBounds(x: Int, y: Int, w: Int, h: Int) {
            this.x = x
            this.y = y
            this.w = w
            this.h = h
        }

        fun cursorEnd() {
            cursor = text.length
        }

        fun draw() {
            drawRect(x, y, x + w, y + h, Color(12, 14, 18, 235).rgb)
            drawRect(x, y, x + w, y + 16, Color(28, 32, 38, 235).rgb)
            mc.fontRendererObj.drawString("Script editor", x + 6, y + 5, Color(180, 187, 198).rgb)

            val lines = text.split('\n')
            val visible = ((h - 24) / 10).coerceAtLeast(1)
            scrollLine = scrollLine.coerceIn(0, (lines.size - visible).coerceAtLeast(0))

            for (i in 0 until visible) {
                val lineIndex = i + scrollLine
                if (lineIndex !in lines.indices) break
                val lineY = y + 22 + i * 10
                mc.fontRendererObj.drawString(trimLine(lines[lineIndex]), x + 8, lineY, Color(220, 224, 230).rgb)
            }

            if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                val cursorPos = cursorPosition()
                if (cursorPos.first in scrollLine until (scrollLine + visible)) {
                    val line = lines.getOrNull(cursorPos.first).orEmpty()
                    val cx = x + 8 + mc.fontRendererObj.getStringWidth(line.take(cursorPos.second))
                    val cy = y + 22 + (cursorPos.first - scrollLine) * 10
                    drawRect(cx, cy - 1, cx + 1, cy + 9, Color.WHITE.rgb)
                }
            }
        }

        fun mouseClicked(mouseX: Int, mouseY: Int, button: Int) {
            focused = mouseX in x..(x + w) && mouseY in y..(y + h)
            if (!focused || button != 0) return

            val lineIndex = scrollLine + ((mouseY - y - 22) / 10)
            val lines = text.split('\n')
            var newCursor = 0
            for (i in 0 until lineIndex.coerceIn(0, lines.size - 1)) {
                newCursor += lines[i].length + 1
            }
            val line = lines.getOrNull(lineIndex).orEmpty()
            var charIndex = 0
            while (charIndex < line.length && mc.fontRendererObj.getStringWidth(line.take(charIndex + 1)) < mouseX - x - 8) {
                charIndex++
            }
            cursor = (newCursor + charIndex).coerceIn(0, text.length)
        }

        fun keyTyped(char: Char, keyCode: Int): Boolean {
            if (!focused) return false

            when {
                isCtrlKeyDown() && keyCode == Keyboard.KEY_A -> {
                    cursor = text.length
                    return true
                }
                isCtrlKeyDown() && keyCode == Keyboard.KEY_V -> {
                    insert(getClipboardString())
                    return true
                }
                keyCode == Keyboard.KEY_BACK -> {
                    if (cursor > 0) {
                        text = text.removeRange(cursor - 1, cursor)
                        cursor--
                    }
                    return true
                }
                keyCode == Keyboard.KEY_DELETE -> {
                    if (cursor < text.length) text = text.removeRange(cursor, cursor + 1)
                    return true
                }
                keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER -> {
                    insert("\n")
                    return true
                }
                keyCode == Keyboard.KEY_TAB -> {
                    insert("    ")
                    return true
                }
                keyCode == Keyboard.KEY_LEFT -> {
                    cursor = (cursor - 1).coerceAtLeast(0)
                    return true
                }
                keyCode == Keyboard.KEY_RIGHT -> {
                    cursor = (cursor + 1).coerceAtMost(text.length)
                    return true
                }
                keyCode == Keyboard.KEY_HOME -> {
                    cursor -= currentColumn()
                    return true
                }
                keyCode == Keyboard.KEY_END -> {
                    val pos = cursorPosition()
                    val line = text.split('\n').getOrNull(pos.first).orEmpty()
                    cursor += line.length - pos.second
                    return true
                }
                char.toInt() >= 32 -> {
                    insert(char.toString())
                    return true
                }
            }
            return false
        }

        fun mouseWheel(wheel: Int) {
            scrollLine += if (wheel < 0) 3 else -3
        }

        private fun insert(value: String) {
            if (value.isEmpty()) return
            text = text.substring(0, cursor) + value + text.substring(cursor)
            cursor += value.length
        }

        private fun cursorPosition(): Pair<Int, Int> {
            var line = 0
            var column = 0
            for (i in 0 until cursor.coerceIn(0, text.length)) {
                if (text[i] == '\n') {
                    line++
                    column = 0
                } else {
                    column++
                }
            }
            return line to column
        }

        private fun currentColumn(): Int = cursorPosition().second

        private fun trimLine(line: String): String {
            var result = line
            while (result.isNotEmpty() && mc.fontRendererObj.getStringWidth(result) > w - 16) {
                result = result.dropLast(1)
            }
            return result
        }
    }
}
