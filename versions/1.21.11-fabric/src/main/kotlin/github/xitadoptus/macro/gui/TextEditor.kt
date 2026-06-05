package github.xitadoptus.macro.gui

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import java.awt.Color

class TextEditor {
    var text: String = ""
    private var focused = false
    private var cursor = 0
    private var scrollLine = 0
    private var x = 0
    private var y = 0
    private var w = 0
    private var h = 0
    private var selectedBracketOffset: Int? = null
    private var selectionAnchor: Int? = null

    fun setBounds(x: Int, y: Int, w: Int, h: Int) {
        this.x = x
        this.y = y
        this.w = w
        this.h = h
    }

    fun cursorEnd() {
        cursor = text.length
        selectionAnchor = null
        selectedBracketOffset = null
    }

    fun render(graphics: GuiGraphics, font: Font) {
        graphics.fill(x, y, x + w, y + h, Color(12, 14, 18, 235).rgb)
        graphics.fill(x, y, x + w, y + 16, Color(28, 32, 38, 235).rgb)
        graphics.drawString(font, MacroFonts.text("Script editor"), x + 6, y + 5, Color(180, 187, 198).rgb, false)

        val lines = text.split('\n')
        val lineStarts = lineStartOffsets(lines)
        val highlight = ScriptSyntaxHighlighter.highlight(text)
        val selectedPair = selectedBracketOffset?.let { highlight.pairAt(it) }
        val selection = selectionRange()
        val visible = ((h - 24) / 10).coerceAtLeast(1)
        scrollLine = scrollLine.coerceIn(0, (lines.size - visible).coerceAtLeast(0))

        for (i in 0 until visible) {
            val lineIndex = i + scrollLine
            if (lineIndex !in lines.indices) break
            val lineY = y + 22 + i * 10
            val line = lines[lineIndex]
            val visibleLine = trimLine(line, font)
            val lineStart = lineStarts[lineIndex]
            drawSelectionBackground(graphics, font, line, visibleLine, lineStart, lineY, selection)
            drawBracketPairBackground(graphics, font, line, visibleLine, lineStart, lineY, selectedPair)
            drawHighlightedLine(graphics, font, line, visibleLine, lineStart, lineY, highlight)
        }

        if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            val cursorPos = cursorPosition()
            if (cursorPos.first in scrollLine until (scrollLine + visible)) {
                val line = lines.getOrNull(cursorPos.first).orEmpty()
                val cx = xForColumn(line, cursorPos.second, font)
                val cy = y + 22 + (cursorPos.first - scrollLine) * 10
                graphics.fill(cx, cy - 1, cx + 1, cy + 9, Color.WHITE.rgb)
            }
        }
    }

    fun mouseClicked(mouseX: Int, mouseY: Int, button: Int, font: Font): Boolean {
        focused = mouseX in x..(x + w) && mouseY in y..(y + h)
        if (!focused || button != 0) return focused

        cursor = offsetAt(mouseX, mouseY, font)
        selectionAnchor = cursor
        selectedBracketOffset = ScriptSyntaxHighlighter.bracketOffsetNear(text, cursor)
            ?.takeIf { ScriptSyntaxHighlighter.highlight(text).pairAt(it) != null }
        return true
    }

    fun mouseDragged(mouseX: Int, mouseY: Int, button: Int, font: Font): Boolean {
        if (!focused || button != 0) return false
        if (selectionAnchor == null) selectionAnchor = cursor
        cursor = offsetAt(mouseX, mouseY, font)
        selectedBracketOffset = null
        return true
    }

    fun keyPressed(keyCode: Int, font: Font, clipboard: String): Boolean {
        if (!focused) return false

        when {
            controlDown() && keyCode == InputConstants.KEY_A -> {
                val all = TextSelectionSupport.selectAll(text.length)
                selectionAnchor = all?.start
                cursor = all?.end ?: text.length
                selectedBracketOffset = null
                return true
            }
            controlDown() && keyCode == InputConstants.KEY_V -> {
                insert(clipboard)
                return true
            }
            keyCode == InputConstants.KEY_BACKSPACE -> {
                applyEdit(TextSelectionSupport.delete(text, selectionRange(), cursor, backwards = true))
                return true
            }
            keyCode == InputConstants.KEY_DELETE -> {
                applyEdit(TextSelectionSupport.delete(text, selectionRange(), cursor, backwards = false))
                return true
            }
            keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER -> {
                insert("\n")
                return true
            }
            keyCode == InputConstants.KEY_TAB -> {
                insert("    ")
                return true
            }
            keyCode == InputConstants.KEY_LEFT -> {
                moveCursor(left = true)
                return true
            }
            keyCode == InputConstants.KEY_RIGHT -> {
                moveCursor(left = false)
                return true
            }
            keyCode == InputConstants.KEY_HOME -> {
                moveCursorTo(cursor - currentColumn())
                return true
            }
            keyCode == InputConstants.KEY_END -> {
                val pos = cursorPosition()
                val line = text.split('\n').getOrNull(pos.first).orEmpty()
                moveCursorTo(cursor + line.length - pos.second)
                return true
            }
        }

        return false
    }

    fun charTyped(char: Char): Boolean {
        if (!focused || char.code < 32) return false
        insert(char.toString())
        return true
    }

    fun mouseWheel(verticalAmount: Double) {
        scrollLine += if (verticalAmount < 0) 3 else -3
    }

    private fun insert(value: String) {
        if (value.isEmpty()) return
        applyEdit(TextSelectionSupport.replace(text, selectionRange(), cursor, value))
    }

    private fun applyEdit(result: TextEditResult) {
        text = result.text
        cursor = result.cursor.coerceIn(0, text.length)
        selectionAnchor = null
        selectedBracketOffset = null
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

    private fun trimLine(line: String, font: Font): String {
        var result = line
        while (result.isNotEmpty() && font.width(MacroFonts.text(result)) > w - 16) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun drawSelectionBackground(
        graphics: GuiGraphics,
        font: Font,
        line: String,
        visibleLine: String,
        lineStart: Int,
        lineY: Int,
        selection: TextSelectionRange?
    ) {
        if (selection == null) return

        val visibleEnd = lineStart + visibleLine.length
        if (selection.end <= lineStart || selection.start >= visibleEnd) return

        val startColumn = (selection.start - lineStart).coerceIn(0, visibleLine.length)
        val endColumn = (selection.end - lineStart).coerceIn(0, visibleLine.length)
        if (endColumn <= startColumn) return

        val startX = xForColumn(line, startColumn, font)
        val endX = xForColumn(line, endColumn, font)
        graphics.fill(startX, lineY - 1, endX.coerceAtLeast(startX + 2), lineY + 10, Color(62, 108, 170, 125).rgb)
    }

    private fun drawHighlightedLine(
        graphics: GuiGraphics,
        font: Font,
        line: String,
        visibleLine: String,
        lineStart: Int,
        lineY: Int,
        highlight: ScriptHighlight
    ) {
        val visibleEnd = lineStart + visibleLine.length
        var drawX = x + 8

        for (span in highlight.spans) {
            if (span.end <= lineStart) continue
            if (span.start >= visibleEnd) break

            val start = span.start.coerceAtLeast(lineStart)
            val end = span.end.coerceAtMost(visibleEnd)
            if (start >= end) continue

            val textStart = start - lineStart
            val textEnd = end - lineStart
            val segment = line.substring(textStart, textEnd)
            graphics.drawString(font, MacroFonts.text(segment), drawX, lineY, opaque(span.color), false)
            drawX += font.width(MacroFonts.text(segment))
        }
    }

    private fun drawBracketPairBackground(
        graphics: GuiGraphics,
        font: Font,
        line: String,
        visibleLine: String,
        lineStart: Int,
        lineY: Int,
        pair: ScriptBracketPair?
    ) {
        if (pair == null) return

        val visibleEnd = lineStart + visibleLine.length
        val positions = intArrayOf(pair.open, pair.close)
        if (positions.any { it in lineStart until visibleEnd }) {
            graphics.fill(x + 6, lineY - 1, x + w - 6, lineY + 10, Color(35, 64, 110, 70).rgb)
        }

        for (position in positions) {
            if (position !in lineStart until visibleEnd) continue
            val column = position - lineStart
            val char = line.getOrNull(column)?.toString() ?: continue
            val charX = xForColumn(line, column, font)
            val charW = font.width(MacroFonts.text(char)).coerceAtLeast(4)
            graphics.fill(charX - 1, lineY - 1, charX + charW + 1, lineY + 10, Color(160, 200, 255, 95).rgb)
        }
    }

    private fun xForColumn(line: String, column: Int, font: Font): Int {
        return x + 8 + font.width(MacroFonts.text(line.take(column.coerceIn(0, line.length))))
    }

    private fun offsetAt(mouseX: Int, mouseY: Int, font: Font): Int {
        val lines = text.split('\n')
        val lineIndex = (scrollLine + ((mouseY - y - 22) / 10)).coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        var offset = 0
        for (i in 0 until lineIndex) {
            offset += lines[i].length + 1
        }

        val line = lines.getOrNull(lineIndex).orEmpty()
        var charIndex = 0
        while (charIndex < line.length && font.width(MacroFonts.text(line.take(charIndex + 1))) < mouseX - x - 8) {
            charIndex++
        }
        return (offset + charIndex).coerceIn(0, text.length)
    }

    private fun selectionRange(): TextSelectionRange? {
        return TextSelectionSupport.range(selectionAnchor, cursor, text.length)
    }

    private fun moveCursor(left: Boolean) {
        val selection = selectionRange()
        if (!shiftDown() && selection != null) {
            cursor = if (left) selection.start else selection.end
            selectionAnchor = null
            selectedBracketOffset = null
            return
        }

        if (shiftDown() && selectionAnchor == null) selectionAnchor = cursor
        cursor = if (left) (cursor - 1).coerceAtLeast(0) else (cursor + 1).coerceAtMost(text.length)
        if (!shiftDown()) selectionAnchor = null
        selectedBracketOffset = null
    }

    private fun moveCursorTo(offset: Int) {
        if (shiftDown() && selectionAnchor == null) selectionAnchor = cursor
        cursor = offset.coerceIn(0, text.length)
        if (!shiftDown()) selectionAnchor = null
        selectedBracketOffset = null
    }

    private fun lineStartOffsets(lines: List<String>): IntArray {
        val starts = IntArray(lines.size)
        var offset = 0
        for (index in lines.indices) {
            starts[index] = offset
            offset += lines[index].length + 1
        }
        return starts
    }

    private fun controlDown(): Boolean = Minecraft.getInstance().hasControlDown()

    private fun shiftDown(): Boolean = Minecraft.getInstance().hasShiftDown()

    private fun opaque(color: Int): Int = color or 0xFF000000.toInt()
}
