package github.xitadoptus.macro.gui

data class TextSelectionRange(
    val start: Int,
    val end: Int
)

data class TextEditResult(
    val text: String,
    val cursor: Int
)

object TextSelectionSupport {
    fun range(anchor: Int?, cursor: Int, length: Int): TextSelectionRange? {
        if (anchor == null) return null
        val safeAnchor = anchor.coerceIn(0, length)
        val safeCursor = cursor.coerceIn(0, length)
        if (safeAnchor == safeCursor) return null
        return if (safeAnchor < safeCursor) TextSelectionRange(safeAnchor, safeCursor) else TextSelectionRange(safeCursor, safeAnchor)
    }

    fun selectAll(length: Int): TextSelectionRange? {
        return if (length > 0) TextSelectionRange(0, length) else null
    }

    fun replace(text: String, selection: TextSelectionRange?, cursor: Int, value: String): TextEditResult {
        if (selection == null) {
            val safeCursor = cursor.coerceIn(0, text.length)
            return TextEditResult(text.substring(0, safeCursor) + value + text.substring(safeCursor), safeCursor + value.length)
        }

        val safeStart = selection.start.coerceIn(0, text.length)
        val safeEnd = selection.end.coerceIn(safeStart, text.length)
        return TextEditResult(text.substring(0, safeStart) + value + text.substring(safeEnd), safeStart + value.length)
    }

    fun delete(text: String, selection: TextSelectionRange?, cursor: Int, backwards: Boolean): TextEditResult {
        if (selection != null) return replace(text, selection, cursor, "")

        val safeCursor = cursor.coerceIn(0, text.length)
        return when {
            backwards && safeCursor > 0 -> TextEditResult(text.removeRange(safeCursor - 1, safeCursor), safeCursor - 1)
            !backwards && safeCursor < text.length -> TextEditResult(text.removeRange(safeCursor, safeCursor + 1), safeCursor)
            else -> TextEditResult(text, safeCursor)
        }
    }
}
