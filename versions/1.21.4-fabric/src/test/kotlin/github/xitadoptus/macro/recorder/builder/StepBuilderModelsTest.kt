package github.xitadoptus.macro.recorder.builder

import github.xitadoptus.macro.engine.MacroEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StepBuilderModelsTest {
    @Test
    fun macroEntryKeepsBuilderDataOptional() {
        assertNull(MacroEntry(name = "Normal").builder)
    }

    @Test
    fun sellActionDefaultsToShiftLeftClick() {
        val action = SellAction()

        assertEquals(ClickButton.LEFT, action.button)
        assertEquals(true, action.shift)
        assertEquals(false, action.control)
        assertEquals(false, action.alt)
    }
}
