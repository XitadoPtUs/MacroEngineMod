package github.xitadoptus.macro.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class MacroConfigTest {
    @Test
    fun usesDefaultRuntimeViewerKey() {
        assertEquals("V", MacroConfig().runtimeViewerKey)
    }
}
