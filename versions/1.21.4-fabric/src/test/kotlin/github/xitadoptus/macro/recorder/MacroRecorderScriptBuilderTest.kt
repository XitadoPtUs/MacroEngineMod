package github.xitadoptus.macro.recorder

import kotlin.test.Test
import kotlin.test.assertEquals

class MacroRecorderScriptBuilderTest {
    @Test
    fun buildsScriptWithWaitsBetweenRecordedActions() {
        val script = MacroRecorderScriptBuilder.build(
            listOf(
                RecordedMacroAction(0, "KEYDOWN(forward)"),
                RecordedMacroAction(250, "KEYUP(forward)"),
                RecordedMacroAction(300, "SLOT(2)")
            )
        )

        assertEquals(
            "$" + "$" + "{\n" +
                "LOG(\"&a[MacroEngine] Recorded macro started\");\n" +
                "KEYDOWN(forward);\n" +
                "WAIT(250ms);\n" +
                "KEYUP(forward);\n" +
                "WAIT(50ms);\n" +
                "SLOT(2);\n" +
                "LOG(\"&a[MacroEngine] Recorded macro finished\");\n" +
                "}" + "$" + "$",
            script
        )
    }

    @Test
    fun buildsEmptyScriptWhenNoActionsWereCaptured() {
        val script = MacroRecorderScriptBuilder.build(emptyList())

        assertEquals(
            "$" + "$" + "{\n" +
                "LOG(\"&e[MacroEngine] Recorder captured no actions\");\n" +
                "}" + "$" + "$",
            script
        )
    }
}
