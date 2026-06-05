package github.xitadoptus.macro.engine

import java.util.Locale
import java.util.regex.Pattern

object MacroScriptParser {
    private const val SCRIPT_START = "$" + "$" + "{"
    private const val SCRIPT_END = "}" + "$" + "$"
    private val actionPattern = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\((.*)\\))?\\s*$", Pattern.DOTALL)

    fun unwrap(script: String): String {
        val trimmed = script.trim()
        return if (trimmed.startsWith(SCRIPT_START) && trimmed.endsWith(SCRIPT_END)) {
            trimmed.substring(SCRIPT_START.length, trimmed.length - SCRIPT_END.length).trim()
        } else {
            script.trim()
        }
    }

    fun parse(script: String): List<MacroStatement> {
        return splitStatements(unwrap(script)).mapNotNull { raw ->
            directAssignment(raw)?.let {
                return@mapNotNull MacroStatement("ASSIGN", listOf(it.first, it.second), raw)
            }

            val matcher = actionPattern.matcher(raw.trim())
            if (!matcher.matches()) {
                if (raw.trim().startsWith("/")) MacroStatement("CHAT", listOf(raw.trim()), raw) else null
            } else {
                val name = matcher.group(1).uppercase(Locale.ROOT)
                val args = matcher.group(2)?.let(::splitArgs).orEmpty()
                MacroStatement(name, args, raw)
            }
        }
    }

    fun splitArgs(input: String): List<String> {
        return splitTopLevel(input, ',').map { it.trim() }
    }

    fun cleanVarName(name: String): String {
        return name.trim()
            .removePrefix("%")
            .removeSuffix("%")
            .removePrefix("&")
            .removePrefix("#")
            .removePrefix("@")
            .removeSuffix("[]")
            .uppercase(Locale.ROOT)
    }

    private fun splitStatements(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var depth = 0

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotBlank()) result += text
            current.setLength(0)
        }

        for (char in input) {
            if (quote != null) {
                current.append(char)
                if (char == quote) quote = null
                continue
            }

            when (char) {
                '"', '\'' -> {
                    quote = char
                    current.append(char)
                }
                '(', '[', '{' -> {
                    depth++
                    current.append(char)
                }
                ')', ']', '}' -> {
                    if (depth > 0) depth--
                    current.append(char)
                }
                ';', '\n', '\r' -> {
                    if (depth == 0) flush() else current.append(char)
                }
                else -> current.append(char)
            }
        }

        flush()
        return result
    }

    private fun splitTopLevel(input: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var depth = 0

        for (char in input) {
            if (quote != null) {
                current.append(char)
                if (char == quote) quote = null
                continue
            }

            when (char) {
                '"', '\'' -> {
                    quote = char
                    current.append(char)
                }
                '(', '[', '{' -> {
                    depth++
                    current.append(char)
                }
                ')', ']', '}' -> {
                    if (depth > 0) depth--
                    current.append(char)
                }
                delimiter -> {
                    if (depth == 0) {
                        result += current.toString()
                        current.setLength(0)
                    } else {
                        current.append(char)
                    }
                }
                else -> current.append(char)
            }
        }

        result += current.toString()
        return result
    }

    private fun directAssignment(raw: String): Pair<String, String>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.first() !in charArrayOf('&', '#', '@', '%')) return null

        var quote: Char? = null
        var depth = 0
        var index = 0
        while (index < trimmed.length) {
            val char = trimmed[index]
            if (quote != null) {
                if (char == quote) quote = null
                index++
                continue
            }

            when (char) {
                '"', '\'' -> quote = char
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                '=' -> {
                    val previous = trimmed.getOrNull(index - 1)
                    val next = trimmed.getOrNull(index + 1)
                    if (depth == 0 && previous != '<' && previous != '>' && previous != '!' && previous != '=' && next != '=') {
                        val left = trimmed.substring(0, index).trim()
                        val right = trimmed.substring(index + 1).trim()
                        if (left.isNotBlank()) return left to right
                    }
                }
            }
            index++
        }

        return null
    }
}
