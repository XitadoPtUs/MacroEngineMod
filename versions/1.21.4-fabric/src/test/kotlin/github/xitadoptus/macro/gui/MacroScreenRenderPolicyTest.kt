package github.xitadoptus.macro.gui

import kotlin.test.Test
import kotlin.test.assertFalse

class MacroScreenRenderPolicyTest {
    @Test
    fun macroScreenDoesNotUseVanillaBlurBackground() {
        assertFalse(MacroScreenRenderPolicy.USE_VANILLA_BACKGROUND)
    }
}
