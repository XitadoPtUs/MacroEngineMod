package github.xitadoptus.macro.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class MacroScriptParserTest {
    @Test
    fun splitsStatementsOutsideQuotesAndParentheses() {
        val script = """
            LOG("a;b")
            SET(&name, "hello, world");
            IF(&name = "hello, world")
            ENDIF
        """.trimIndent()

        val statements = MacroScriptParser.parse(script)

        assertEquals(listOf("LOG", "SET", "IF", "ENDIF"), statements.map { it.name })
        assertEquals(listOf("\"a;b\""), statements[0].args)
        assertEquals(listOf("&name", "\"hello, world\""), statements[1].args)
    }

    @Test
    fun unwrapsMacroBlocks() {
        val script = "$" + "$" + "{\nLOG(\"ok\")\n}" + "$" + "$"

        assertEquals("LOG(\"ok\")", MacroScriptParser.unwrap(script))
    }

    @Test
    fun convertsDirectAssignmentsToAssignStatements() {
        val statements = MacroScriptParser.parse("&value = 2 + 3 * 4")

        assertEquals("ASSIGN", statements.single().name)
        assertEquals(listOf("&value", "2 + 3 * 4"), statements.single().args)
    }
}
