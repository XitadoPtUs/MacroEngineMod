package github.xitadoptus.macro.recorder

import kotlin.test.Test
import kotlin.test.assertEquals

class RecordedInputTrackerTest {
    @Test
    fun emitsDownAndUpTransitions() {
        val tracker = RecordedInputTracker(listOf("forward", "jump"))

        assertEquals(emptyList(), tracker.update(0, mapOf("forward" to false, "jump" to false)))
        assertEquals(listOf(RecordedMacroAction(50, "KEYDOWN(forward)")), tracker.update(50, mapOf("forward" to true, "jump" to false)))
        assertEquals(listOf(RecordedMacroAction(100, "KEYDOWN(jump)")), tracker.update(100, mapOf("forward" to true, "jump" to true)))
        assertEquals(listOf(RecordedMacroAction(150, "KEYUP(forward)")), tracker.update(150, mapOf("forward" to false, "jump" to true)))
    }

    @Test
    fun releasesHeldKeysWhenRecordingStops() {
        val tracker = RecordedInputTracker(listOf("forward", "attack"))

        tracker.update(0, mapOf("forward" to true, "attack" to true))

        assertEquals(
            listOf(
                RecordedMacroAction(400, "KEYUP(forward)"),
                RecordedMacroAction(400, "KEYUP(attack)")
            ),
            tracker.releaseHeld(400)
        )
    }
}
