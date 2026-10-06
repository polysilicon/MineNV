package dev.osl.client.mixin;

import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Development only (-Dosl.devNoSrgb=true): virtual displays used for automated tests (Xvfb + Mesa) have no
 * sRGB-capable GLX visual, so Minecraft can't open its window there. Players never set the property.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlBackend")
abstract class GlBackendDevMixin {
	private static final int SDL_GL_FRAMEBUFFER_SRGB_CAPABLE = 22;

	@Redirect(method = "createWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SetAttribute(II)Z"), remap = false)
	private boolean osl$devAttribute(final int attribute, final int value) {
		if (attribute == SDL_GL_FRAMEBUFFER_SRGB_CAPABLE && Boolean.getBoolean("osl.devNoSrgb")) {
			return true;
		}

		return SDLVideo.SDL_GL_SetAttribute(attribute, value);
	}
}
