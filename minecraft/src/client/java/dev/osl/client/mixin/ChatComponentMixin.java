package dev.osl.client.mixin;

import dev.osl.client.Friends;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Every chat line passes by Friends, which picks e4mc's address for the host's world out of it (unchanged). */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin {
	@ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component osl$watchForLink(final Component contents) {
		Friends.onChat(contents);
		return contents;
	}
}
