package github.xitadoptus.macro.gui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScriptSyntaxHighlighterTest {
    @Test
    fun givesNestedBracketsDifferentRainbowColors() {
        val script = "$" + "$" + "{\nLOG(MAX(1, MIN(2, 3)))\n}" + "$" + "$"
        val highlight = ScriptSyntaxHighlighter.highlight(script)

        val firstOpen = script.indexOf('(')
        val secondOpen = script.indexOf('(', firstOpen + 1)
        val thirdOpen = script.indexOf('(', secondOpen + 1)

        val firstColor = highlight.spanAt(firstOpen)?.color
        val secondColor = highlight.spanAt(secondOpen)?.color
        val thirdColor = highlight.spanAt(thirdOpen)?.color

        assertNotNull(firstColor)
        assertNotNull(secondColor)
        assertNotNull(thirdColor)
        assertNotEquals(firstColor, secondColor)
        assertNotEquals(secondColor, thirdColor)
    }

    @Test
    fun findsMatchingBracketFromEitherSide() {
        val script = "$" + "$" + "{\nIF(TRUE)\nLOG(\"ok\")\nENDIF\n}" + "$" + "$"
        val highlight = ScriptSyntaxHighlighter.highlight(script)
        val open = script.indexOf('{')
        val close = script.lastIndexOf('}')

        assertEquals(ScriptBracketPair(open, close), highlight.pairAt(open))
        assertEquals(ScriptBracketPair(open, close), highlight.pairAt(close))
    }

    @Test
    fun ignoresBracketsInsideStringsForMatching() {
        val script = "LOG(\"{\")"
        val highlight = ScriptSyntaxHighlighter.highlight(script)

        assertNull(highlight.pairAt(script.indexOf('{')))
    }

    @Test
    fun colorsCommandsStringsVariablesAndNumbers() {
        val script = "SET(&name, \"abc\", 15)"
        val highlight = ScriptSyntaxHighlighter.highlight(script)

        assertEquals(ScriptSyntaxHighlighter.COMMAND_COLOR, highlight.spanAt(script.indexOf("SET"))?.color)
        assertEquals(ScriptSyntaxHighlighter.VARIABLE_COLOR, highlight.spanAt(script.indexOf("&name"))?.color)
        assertEquals(ScriptSyntaxHighlighter.STRING_COLOR, highlight.spanAt(script.indexOf("\"abc\""))?.color)
        assertEquals(ScriptSyntaxHighlighter.NUMBER_COLOR, highlight.spanAt(script.indexOf("15"))?.color)
    }
}
