package github.xitadoptus.macro.gui

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FormattedCharSequence

object MacroFonts {
    val JETBRAINS_MONO: ResourceLocation = ResourceLocation.fromNamespaceAndPath("macroengine", "jetbrains_mono")
    val STYLE: Style = Style.EMPTY.withFont(JETBRAINS_MONO)

    fun text(value: String): Component {
        return Component.literal(value).withStyle(STYLE)
    }

    fun sequence(value: String): FormattedCharSequence {
        return FormattedCharSequence.forward(value, STYLE)
    }
}
