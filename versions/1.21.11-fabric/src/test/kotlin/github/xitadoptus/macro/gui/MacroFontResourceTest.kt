package github.xitadoptus.macro.gui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MacroFontResourceTest {
    @Test
    fun jetbrainsMonoFontResourcesExist() {
        assertNotNull(javaClass.classLoader.getResource("assets/macroengine/font/jetbrains_mono.json"))
        assertNotNull(javaClass.classLoader.getResource("assets/macroengine/font/jetbrains_mono_semibold.ttf"))
    }

    @Test
    fun macroFontUsesMacroengineNamespace() {
        assertTrue(MacroFonts.JETBRAINS_MONO.toString().startsWith("macroengine:"))
    }

    @Test
    fun ttfProviderPathDoesNotDuplicateFontDirectory() {
        val fontJson = javaClass.classLoader
            .getResource("assets/macroengine/font/jetbrains_mono.json")
            ?.readText()
            .orEmpty()

        assertTrue(fontJson.contains("\"file\": \"macroengine:jetbrains_mono_semibold.ttf\""))
        assertFalse(fontJson.contains("macroengine:font/jetbrains_mono_semibold.ttf"))
    }
}
