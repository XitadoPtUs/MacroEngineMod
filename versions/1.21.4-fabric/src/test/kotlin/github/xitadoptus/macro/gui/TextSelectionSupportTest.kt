package github.xitadoptus.macro.gui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextSelectionSupportTest {
    @Test
    fun createsSortedSelectionRangeFromAnchorAndCursor() {
        assertEquals(TextSelectionRange(2, 7), TextSelectionSupport.range(7, 2, 10))
        assertEquals(TextSelectionRange(2, 7), TextSelectionSupport.range(2, 7, 10))
    }

    @Test
    fun ignoresCollapsedSelectionRange() {
        assertNull(TextSelectionSupport.range(4, 4, 10))
    }

    @Test
    fun replacesSelectedTextAndMovesCursorToReplacementEnd() {
        val result = TextSelectionSupport.replace("LOG(\"test\")", TextSelectionRange(4, 10), 10, "\"ok\"")

        assertEquals("LOG(\"ok\")", result.text)
        assertEquals(8, result.cursor)
    }

    @Test
    fun deleteRemovesSelectedTextBeforeSingleCharacterFallback() {
        val result = TextSelectionSupport.delete("abcdef", TextSelectionRange(1, 5), 5, backwards = true)

        assertEquals("af", result.text)
        assertEquals(1, result.cursor)
    }

    @Test
    fun ctrlASelectsWholeText() {
        assertEquals(TextSelectionRange(0, 6), TextSelectionSupport.selectAll(6))
    }
}
