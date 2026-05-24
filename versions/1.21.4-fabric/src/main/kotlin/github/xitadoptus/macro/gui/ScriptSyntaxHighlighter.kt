package github.xitadoptus.macro.gui

import java.util.Locale

data class ScriptHighlightSpan(
    val start: Int,
    val end: Int,
    val color: Int
)

data class ScriptBracketPair(
    val open: Int,
    val close: Int
)

data class ScriptHighlight(
    val spans: List<ScriptHighlightSpan>,
    val pairs: List<ScriptBracketPair>
) {
    fun spanAt(offset: Int): ScriptHighlightSpan? {
        return spans.firstOrNull { offset in it.start until it.end }
    }

    fun pairAt(offset: Int): ScriptBracketPair? {
        return pairs.firstOrNull { it.open == offset || it.close == offset }
    }
}

object ScriptSyntaxHighlighter {
    const val DEFAULT_COLOR = 0xDDE3EA
    const val COMMAND_COLOR = 0x82AAFF
    const val KEYWORD_COLOR = 0xC792EA
    const val STRING_COLOR = 0xC3E88D
    const val NUMBER_COLOR = 0xF78C6C
    const val VARIABLE_COLOR = 0xFFCB6B
    const val PARAMETER_COLOR = 0x89DDFF
    const val OPERATOR_COLOR = 0xB8C4D6
    const val ERROR_COLOR = 0xFF5370

    private val bracketColors = intArrayOf(
        0xFFCB6B,
        0x82AAFF,
        0xC792EA,
        0xC3E88D,
        0xF78C6C,
        0x89DDFF
    )
    private val openToClose = mapOf('(' to ')', '[' to ']', '{' to '}')
    private val closeToOpen = openToClose.entries.associate { it.value to it.key }
    private val keywords = setOf(
        "IF",
        "IFCONTAINS",
        "IFBEGINSWITH",
        "IFENDSWITH",
        "IFMATCHES",
        "ELSE",
        "ELSEIF",
        "ENDIF",
        "DO",
        "WHILE",
        "UNTIL",
        "LOOP",
        "FOR",
        "FOREACH",
        "NEXT",
        "BREAK",
        "UNSAFE",
        "ENDUNSAFE"
    )

    fun highlight(text: String): ScriptHighlight {
        val spans = mutableListOf<ScriptHighlightSpan>()
        val pairs = mutableListOf<ScriptBracketPair>()
        val stack = mutableListOf<OpenBracket>()
        var index = 0

        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' || char == '\'' -> {
                    val end = stringEnd(text, index, char)
                    spans += ScriptHighlightSpan(index, end, STRING_COLOR)
                    index = end
                }
                text.startsWith("$$", index) -> {
                    spans += ScriptHighlightSpan(index, index + 2, PARAMETER_COLOR)
                    index += 2
                }
                char in openToClose.keys -> {
                    val depth = stack.size
                    stack += OpenBracket(index, char, depth)
                    spans += ScriptHighlightSpan(index, index + 1, bracketColor(depth))
                    index++
                }
                char in closeToOpen.keys -> {
                    val open = stack.lastOrNull()
                    if (open != null && closeToOpen[char] == open.char) {
                        stack.removeAt(stack.lastIndex)
                        pairs += ScriptBracketPair(open.index, index)
                        spans += ScriptHighlightSpan(index, index + 1, bracketColor(open.depth))
                    } else {
                        spans += ScriptHighlightSpan(index, index + 1, ERROR_COLOR)
                    }
                    index++
                }
                char == '&' || char == '#' || char == '@' || char == '%' -> {
                    val end = variableEnd(text, index, char)
                    spans += ScriptHighlightSpan(index, end, VARIABLE_COLOR)
                    index = end
                }
                char.isDigit() -> {
                    val end = numberEnd(text, index)
                    spans += ScriptHighlightSpan(index, end, NUMBER_COLOR)
                    index = end
                }
                char.isIdentifierStart() -> {
                    val end = identifierEnd(text, index)
                    val word = text.substring(index, end).uppercase(Locale.ROOT)
                    val color = when {
                        word in keywords -> KEYWORD_COLOR
                        nextNonWhitespace(text, end) == '(' -> COMMAND_COLOR
                        else -> DEFAULT_COLOR
                    }
                    spans += ScriptHighlightSpan(index, end, color)
                    index = end
                }
                char.isOperator() -> {
                    spans += ScriptHighlightSpan(index, index + 1, OPERATOR_COLOR)
                    index++
                }
                else -> {
                    val end = defaultEnd(text, index)
                    spans += ScriptHighlightSpan(index, end, DEFAULT_COLOR)
                    index = end
                }
            }
        }

        return ScriptHighlight(spans, pairs)
    }

    fun bracketOffsetNear(text: String, offset: Int): Int? {
        if (text.isEmpty()) return null
        val right = offset.coerceIn(0, text.lastIndex)
        if (isBracket(text[right])) return right
        val left = offset - 1
        return if (left in text.indices && isBracket(text[left])) left else null
    }

    private fun bracketColor(depth: Int): Int {
        return bracketColors[depth.mod(bracketColors.size)]
    }

    private fun stringEnd(text: String, start: Int, quote: Char): Int {
        var index = start + 1
        var escaped = false
        while (index < text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == quote -> return index + 1
            }
            index++
        }
        return text.length
    }

    private fun variableEnd(text: String, start: Int, prefix: Char): Int {
        if (prefix == '%') {
            val close = text.indexOf('%', start + 1)
            if (close > start) return close + 1
        }

        var index = start + 1
        while (index < text.length && text[index].isVariablePart()) index++
        return index.coerceAtLeast(start + 1)
    }

    private fun numberEnd(text: String, start: Int): Int {
        var index = start
        var dot = false
        while (index < text.length) {
            val char = text[index]
            if (char == '.' && !dot) {
                dot = true
                index++
            } else if (char.isDigit()) {
                index++
            } else {
                break
            }
        }
        return index
    }

    private fun identifierEnd(text: String, start: Int): Int {
        var index = start + 1
        while (index < text.length && text[index].isIdentifierPart()) index++
        return index
    }

    private fun defaultEnd(text: String, start: Int): Int {
        var index = start + 1
        while (index < text.length && !text[index].startsToken()) index++
        return index
    }

    private fun nextNonWhitespace(text: String, start: Int): Char? {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return text.getOrNull(index)
    }

    private fun isBracket(char: Char): Boolean {
        return char in openToClose.keys || char in closeToOpen.keys
    }

    private fun Char.startsToken(): Boolean {
        return this == '"' ||
            this == '\'' ||
            this == '$' ||
            this == '&' ||
            this == '#' ||
            this == '@' ||
            this == '%' ||
            this in openToClose.keys ||
            this in closeToOpen.keys ||
            isDigit() ||
            isIdentifierStart() ||
            isOperator()
    }

    private fun Char.isIdentifierStart(): Boolean {
        return this == '_' || isLetter()
    }

    private fun Char.isIdentifierPart(): Boolean {
        return isIdentifierStart() || isDigit()
    }

    private fun Char.isVariablePart(): Boolean {
        return isIdentifierPart() || this == '[' || this == ']' || this == '.'
    }

    private fun Char.isOperator(): Boolean {
        return this in charArrayOf('=', '+', '-', '*', '/', '!', '<', '>', ',', ';', ':')
    }

    private data class OpenBracket(
        val index: Int,
        val char: Char,
        val depth: Int
    )
}
