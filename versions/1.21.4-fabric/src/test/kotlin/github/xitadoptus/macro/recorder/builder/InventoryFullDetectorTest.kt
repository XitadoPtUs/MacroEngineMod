package github.xitadoptus.macro.recorder.builder

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InventoryFullDetectorTest {
    @Test
    fun reportsFullWhenNoMainInventorySlotIsEmpty() {
        assertTrue(InventoryFullDetector.isFull(List(36) { false }))
    }

    @Test
    fun reportsNotFullWhenAnyMainInventorySlotIsEmpty() {
        assertFalse(InventoryFullDetector.isFull(List(35) { false } + true))
    }
}
