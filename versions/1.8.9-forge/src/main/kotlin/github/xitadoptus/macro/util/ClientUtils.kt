package github.xitadoptus.macro.util

import net.minecraft.client.Minecraft
import net.minecraft.util.ChatComponentText
import org.apache.logging.log4j.LogManager

object ClientUtils {
    private val logger = LogManager.getLogger("MacroEngine")

    fun displayChatMessage(message: String) {
        val mc = Minecraft.getMinecraft()
        val player = mc.thePlayer
        if (player != null) {
            player.addChatMessage(ChatComponentText(message))
        } else {
            logger.info(message)
        }
    }

    fun logInfo(message: String) {
        logger.info(message)
    }

    fun logError(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            logger.error(message)
        } else {
            logger.error(message, throwable)
        }
    }
}
