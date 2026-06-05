package github.xitadoptus.macro.util

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

object ClientUtils {
    private val logger = LoggerFactory.getLogger("MacroEngine")
    private val colorPattern = Regex("\u00A7.")

    fun displayChatMessage(message: String) {
        val mc = Minecraft.getInstance()
        val action = Runnable {
            val formatted = color(message)
            val player = mc.player
            if (player != null) {
                player.displayClientMessage(Component.literal(formatted), false)
            } else {
                logger.info(stripColors(formatted))
            }
        }

        if (mc.isSameThread) action.run() else mc.execute(action)
    }

    fun sendChat(message: String) {
        val text = message.trim()
        if (text.isBlank()) return

        val mc = Minecraft.getInstance()
        val action = Runnable {
            val connection = mc.player?.connection ?: return@Runnable
            if (text.startsWith("/")) {
                connection.sendCommand(text.drop(1))
            } else {
                connection.sendChat(text)
            }
        }

        if (mc.isSameThread) action.run() else mc.execute(action)
    }

    fun logInfo(message: String) {
        logger.info(message)
    }

    fun logError(message: String, throwable: Throwable? = null) {
        if (throwable == null) logger.error(message) else logger.error(message, throwable)
    }

    fun color(message: String): String {
        return message.replace('&', '\u00A7')
    }

    fun stripColors(message: String): String {
        return message.replace(colorPattern, "")
    }
}
