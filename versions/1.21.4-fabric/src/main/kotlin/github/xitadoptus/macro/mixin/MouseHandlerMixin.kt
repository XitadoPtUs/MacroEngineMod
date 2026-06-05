package github.xitadoptus.macro.mixin

import github.xitadoptus.macro.gui.MacroRuntimeViewerOverlay
import net.minecraft.client.MouseHandler
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(MouseHandler::class)
abstract class MouseHandlerMixin {
    @Inject(method = ["onPress"], at = [At("HEAD")], cancellable = true)
    private fun onPress(window: Long, button: Int, action: Int, modifiers: Int, callbackInfo: CallbackInfo) {
        if (MacroRuntimeViewerOverlay.handleMouseButton(window, button, action)) callbackInfo.cancel()
    }
}
