package github.xitadoptus.macro.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class MacroConfigTest {
    @Test
    fun usesDefaultGlobalStopKey() {
        assertEquals("END", MacroConfig().macroStopKey)
    }

    @Test
    fun usesDefaultRuntimeViewerKey() {
        assertEquals("V", MacroConfig().runtimeViewerKey)
    }
}
