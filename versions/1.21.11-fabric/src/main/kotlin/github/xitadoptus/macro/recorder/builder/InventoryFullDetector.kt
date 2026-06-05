package github.xitadoptus.macro.recorder.builder

import net.minecraft.client.Minecraft

object InventoryFullDetector {
    fun isFull(emptySlots: List<Boolean>): Boolean {
        return emptySlots.isNotEmpty() && emptySlots.none { it }
    }

    fun isClientInventoryFull(client: Minecraft): Boolean {
        val items = client.player?.inventory?.getNonEquipmentItems() ?: return false
        return isFull(items.take(36).map { it.isEmpty })
    }
}
