package dev.osl.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.osl.client.OslClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Started hidden behind New Vegas (-Dosl.startHidden=true): Minecraft's window counts as focused while New Vegas is
 * linked (so it never pauses or lets go of input), and never as minimized (so it keeps rendering at full rate).
 */
@Mixin(Window.class)
abstract class WindowMixin {
	@Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
	private void osl$focused(final CallbackInfoReturnable<Boolean> cir) {
		if (OslClient.START_HIDDEN) {
			cir.setReturnValue(OslClient.linked());
		}
	}

	@Inject(method = "isIconified", at = @At("HEAD"), cancellable = true)
	private void osl$notIconified(final CallbackInfoReturnable<Boolean> cir) {
		if (OslClient.START_HIDDEN) {
			cir.setReturnValue(false);
		}
	}
}
