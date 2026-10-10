package dev.osl.client.mixin;

import dev.osl.NpcWar;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * This player's own New Vegas NPC stand-ins aren't drawn (their New Vegas draws the real NPCs there), but can still
 * be hit. Friends see them.
 */
@Mixin(Entity.class)
abstract class OwnProxyMixin {
	@Inject(method = "isInvisible()Z", at = @At("HEAD"), cancellable = true)
	private void osl$hideOwnProxy(final CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (!NpcWar.HIDDEN.isEmpty() && self.level().isClientSide() && NpcWar.HIDDEN.contains(self.getId())) {
			cir.setReturnValue(true);
		}
	}
}
