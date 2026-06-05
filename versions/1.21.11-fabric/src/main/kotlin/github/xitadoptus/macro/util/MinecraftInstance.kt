package github.xitadoptus.macro.util

import net.minecraft.client.Minecraft

open class MinecraftInstance {
    protected val mc: Minecraft
        get() = Minecraft.getInstance()
}
