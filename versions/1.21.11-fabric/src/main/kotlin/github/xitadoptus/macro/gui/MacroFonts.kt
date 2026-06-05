package github.xitadoptus.macro.gui

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence

object MacroFonts {
    val JETBRAINS_MONO: Identifier = Identifier.fromNamespaceAndPath("macroengine", "jetbrains_mono")
    val STYLE: Style = Style.EMPTY.withFont(FontDescription.Resource(JETBRAINS_MONO))

    fun text(value: String): Component {
        return Component.literal(value).withStyle(STYLE)
    }

    fun sequence(value: String): FormattedCharSequence {
        return FormattedCharSequence.forward(value, STYLE)
    }
}
