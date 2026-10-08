package dev.osl.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.osl.client.InputBridge;
import dev.osl.client.Takeover;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft mode: held keys come from New Vegas (shift-click, sprint, sneak), and the hidden window never grabs the real mouse. */
@Mixin(InputConstants.class)
abstract class InputConstantsMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private static void osl$isKeyDown(final int key, final CallbackInfoReturnable<Boolean> cir) {
		if (Takeover.on()) {
			cir.setReturnValue(InputBridge.isKeyDown(key));
		}
	}

	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private static void osl$grabMouse(final Window window, final double xpos, final double ypos, final CallbackInfo ci) {
		if (Takeover.on()) {
			ci.cancel();
		}
	}

	@Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
	private static void osl$releaseMouse(final Window window, final double xpos, final double ypos, final CallbackInfo ci) {
		if (Takeover.on()) {
			ci.cancel();
		}
	}
}
