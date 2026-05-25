package github.xitadoptus.macro.util

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

object KeyboardUtils {
    private val keyCodes = buildKeyMap()
    private val aliases = mapOf(
        "ESC" to "ESCAPE",
        "BACK" to "BACKSPACE",
        "DEL" to "DELETE",
        "PGUP" to "PAGEUP",
        "PGDN" to "PAGEDOWN",
        "CTRL" to "LCTRL",
        "CONTROL" to "LCTRL",
        "SHIFT" to "LSHIFT",
        "ALT" to "LALT",
        "SPACEBAR" to "SPACE",
        "NUMPADENTER" to "NUMPADENTER"
    )
    private val mouseCodes = mapOf(
        "MOUSE1" to GLFW.GLFW_MOUSE_BUTTON_LEFT,
        "LMOUSE" to GLFW.GLFW_MOUSE_BUTTON_LEFT,
        "LEFTMOUSE" to GLFW.GLFW_MOUSE_BUTTON_LEFT,
        "MOUSE2" to GLFW.GLFW_MOUSE_BUTTON_RIGHT,
        "RMOUSE" to GLFW.GLFW_MOUSE_BUTTON_RIGHT,
        "RIGHTMOUSE" to GLFW.GLFW_MOUSE_BUTTON_RIGHT,
        "MOUSE3" to GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
        "MIDDLEMOUSE" to GLFW.GLFW_MOUSE_BUTTON_MIDDLE
    )

    fun normalizeKey(raw: String): String {
        val normalized = KeyNameNormalizer.normalize(raw)
        return aliases[normalized] ?: normalized
    }

    fun keyCode(raw: String): Int {
        val key = normalizeKey(raw)
        return keyCodes[key] ?: mouseCodes[key] ?: -1
    }

    fun keyNameFromCode(keyCode: Int): String {
        return keyCodes.entries.firstOrNull { it.value == keyCode }?.key
            ?: runCatching { GLFW.glfwGetKeyName(keyCode, 0)?.uppercase() }.getOrNull()
            ?: "NONE"
    }

    fun isValidKeyName(raw: String): Boolean {
        return keyCode(raw) >= 0
    }

    fun isKeyPressed(raw: String): Boolean {
        val code = keyCodes[normalizeKey(raw)] ?: return false
        return InputConstants.isKeyDown(Minecraft.getInstance().window.window, code)
    }

    fun isInputPressed(raw: String): Boolean {
        val key = normalizeKey(raw)
        val mouse = mouseCodes[key]
        if (mouse != null) {
            return GLFW.glfwGetMouseButton(Minecraft.getInstance().window.window, mouse) == GLFW.GLFW_PRESS
        }
        return isKeyPressed(raw)
    }

    fun inputKey(raw: String): InputConstants.Key? {
        val key = normalizeKey(raw)
        mouseCodes[key]?.let { return InputConstants.Type.MOUSE.getOrCreate(it) }
        val code = keyCodes[key] ?: return null
        return InputConstants.Type.KEYSYM.getOrCreate(code)
    }

    fun pulse(raw: String) {
        val key = inputKey(raw) ?: return
        KeyMapping.click(key)
    }

    fun set(raw: String, down: Boolean) {
        val key = inputKey(raw) ?: return
        KeyMapping.set(key, down)
    }

    private fun buildKeyMap(): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (char in 'A'..'Z') map[char.toString()] = char.code
        for (char in '0'..'9') map[char.toString()] = char.code
        for (index in 1..25) map["F$index"] = GLFW.GLFW_KEY_F1 + index - 1

        map += mapOf(
            "NONE" to -1,
            "ENTER" to GLFW.GLFW_KEY_ENTER,
            "RETURN" to GLFW.GLFW_KEY_ENTER,
            "ESCAPE" to GLFW.GLFW_KEY_ESCAPE,
            "BACKSPACE" to GLFW.GLFW_KEY_BACKSPACE,
            "DELETE" to GLFW.GLFW_KEY_DELETE,
            "TAB" to GLFW.GLFW_KEY_TAB,
            "SPACE" to GLFW.GLFW_KEY_SPACE,
            "UP" to GLFW.GLFW_KEY_UP,
            "DOWN" to GLFW.GLFW_KEY_DOWN,
            "LEFT" to GLFW.GLFW_KEY_LEFT,
            "RIGHT" to GLFW.GLFW_KEY_RIGHT,
            "HOME" to GLFW.GLFW_KEY_HOME,
            "END" to GLFW.GLFW_KEY_END,
            "INSERT" to GLFW.GLFW_KEY_INSERT,
            "PAGEUP" to GLFW.GLFW_KEY_PAGE_UP,
            "PAGEDOWN" to GLFW.GLFW_KEY_PAGE_DOWN,
            "CAPSLOCK" to GLFW.GLFW_KEY_CAPS_LOCK,
            "NUMLOCK" to GLFW.GLFW_KEY_NUM_LOCK,
            "SCROLLLOCK" to GLFW.GLFW_KEY_SCROLL_LOCK,
            "PAUSE" to GLFW.GLFW_KEY_PAUSE,
            "PRINTSCREEN" to GLFW.GLFW_KEY_PRINT_SCREEN,
            "LCTRL" to GLFW.GLFW_KEY_LEFT_CONTROL,
            "RCTRL" to GLFW.GLFW_KEY_RIGHT_CONTROL,
            "LSHIFT" to GLFW.GLFW_KEY_LEFT_SHIFT,
            "RSHIFT" to GLFW.GLFW_KEY_RIGHT_SHIFT,
            "LALT" to GLFW.GLFW_KEY_LEFT_ALT,
            "RALT" to GLFW.GLFW_KEY_RIGHT_ALT,
            "MINUS" to GLFW.GLFW_KEY_MINUS,
            "EQUALS" to GLFW.GLFW_KEY_EQUAL,
            "LBRACKET" to GLFW.GLFW_KEY_LEFT_BRACKET,
            "RBRACKET" to GLFW.GLFW_KEY_RIGHT_BRACKET,
            "SEMICOLON" to GLFW.GLFW_KEY_SEMICOLON,
            "APOSTROPHE" to GLFW.GLFW_KEY_APOSTROPHE,
            "GRAVE" to GLFW.GLFW_KEY_GRAVE_ACCENT,
            "BACKSLASH" to GLFW.GLFW_KEY_BACKSLASH,
            "COMMA" to GLFW.GLFW_KEY_COMMA,
            "PERIOD" to GLFW.GLFW_KEY_PERIOD,
            "SLASH" to GLFW.GLFW_KEY_SLASH,
            "ADD" to GLFW.GLFW_KEY_KP_ADD,
            "MULTIPLY" to GLFW.GLFW_KEY_KP_MULTIPLY,
            "NUMPAD0" to GLFW.GLFW_KEY_KP_0,
            "NUMPAD1" to GLFW.GLFW_KEY_KP_1,
            "NUMPAD2" to GLFW.GLFW_KEY_KP_2,
            "NUMPAD3" to GLFW.GLFW_KEY_KP_3,
            "NUMPAD4" to GLFW.GLFW_KEY_KP_4,
            "NUMPAD5" to GLFW.GLFW_KEY_KP_5,
            "NUMPAD6" to GLFW.GLFW_KEY_KP_6,
            "NUMPAD7" to GLFW.GLFW_KEY_KP_7,
            "NUMPAD8" to GLFW.GLFW_KEY_KP_8,
            "NUMPAD9" to GLFW.GLFW_KEY_KP_9,
            "NUMPADENTER" to GLFW.GLFW_KEY_KP_ENTER
        )
        return map
    }
}
