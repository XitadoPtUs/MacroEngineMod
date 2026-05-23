package github.xitadoptus.macro.util

import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import java.util.Locale

object KeyboardUtils {
    fun normalizeKey(raw: String): String {
        return when (raw.trim().toUpperCase(Locale.ROOT)) {
            "RETURN" -> "ENTER"
            "PRIOR" -> "PAGEUP"
            "NEXT" -> "PAGEDOWN"
            "LMENU" -> "LALT"
            "RMENU" -> "RALT"
            "LCONTROL" -> "LCTRL"
            "RCONTROL" -> "RCTRL"
            "CAPITAL" -> "CAPSLOCK"
            "" -> "NONE"
            else -> raw.trim().toUpperCase(Locale.ROOT)
        }
    }

    fun keyCode(raw: String): Int {
        return when (val key = normalizeKey(raw)) {
            "NONE" -> Keyboard.KEY_NONE
            "ENTER" -> Keyboard.KEY_RETURN
            "PAGEUP" -> Keyboard.KEY_PRIOR
            "PAGEDOWN" -> Keyboard.KEY_NEXT
            "LALT" -> Keyboard.KEY_LMENU
            "RALT" -> Keyboard.KEY_RMENU
            "LCTRL" -> Keyboard.KEY_LCONTROL
            "RCTRL" -> Keyboard.KEY_RCONTROL
            "CAPSLOCK" -> Keyboard.KEY_CAPITAL
            else -> Keyboard.getKeyIndex(key)
        }
    }

    fun isValidKeyName(raw: String): Boolean {
        return keyCode(raw) != Keyboard.KEY_NONE
    }

    fun isKeyPressed(raw: String): Boolean {
        val code = keyCode(raw)
        return code != Keyboard.KEY_NONE && Keyboard.isKeyDown(code)
    }

    fun isInputPressed(raw: String): Boolean {
        return when (normalizeKey(raw)) {
            "MOUSE1", "LMOUSE", "LEFTMOUSE" -> Mouse.isButtonDown(0)
            "MOUSE2", "RMOUSE", "RIGHTMOUSE" -> Mouse.isButtonDown(1)
            "MOUSE3", "MIDDLEMOUSE" -> Mouse.isButtonDown(2)
            else -> isKeyPressed(raw)
        }
    }
}
