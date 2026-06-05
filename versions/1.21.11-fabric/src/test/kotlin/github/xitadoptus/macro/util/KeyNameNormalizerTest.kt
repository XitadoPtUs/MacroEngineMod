package github.xitadoptus.macro.util

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyNameNormalizerTest {
    @Test
    fun normalizesLegacyAliases() {
        assertEquals("ENTER", KeyNameNormalizer.normalize("return"))
        assertEquals("PAGEUP", KeyNameNormalizer.normalize("prior"))
        assertEquals("LCTRL", KeyNameNormalizer.normalize("lcontrol"))
        assertEquals("NONE", KeyNameNormalizer.normalize(" "))
    }

    @Test
    fun keepsCanonicalMouseNames() {
        assertEquals("MOUSE1", KeyNameNormalizer.normalize("mouse1"))
        assertEquals("RIGHTMOUSE", KeyNameNormalizer.normalize("rightmouse"))
    }

    @Test
    fun mapsGlfwKeyCodesWithoutRuntimeReflection() {
        assertEquals("J", KeyboardUtils.keyNameFromCode(74))
        assertEquals("ENTER", KeyboardUtils.keyNameFromCode(257))
    }
}
