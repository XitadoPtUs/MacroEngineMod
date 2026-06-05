package github.xitadoptus.macro.util

import java.util.Locale

object KeyNameNormalizer {
    fun normalize(raw: String): String {
        return when (raw.trim().uppercase(Locale.ROOT)) {
            "RETURN" -> "ENTER"
            "PRIOR" -> "PAGEUP"
            "NEXT" -> "PAGEDOWN"
            "LMENU" -> "LALT"
            "RMENU" -> "RALT"
            "LCONTROL" -> "LCTRL"
            "RCONTROL" -> "RCTRL"
            "CAPITAL" -> "CAPSLOCK"
            "" -> "NONE"
            else -> raw.trim().uppercase(Locale.ROOT)
        }
    }
}
