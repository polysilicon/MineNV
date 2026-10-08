package dev.osl.mixin;

import dev.osl.NpcWar;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** New Vegas NPC stand-ins: a hit goes to the real NPC in New Vegas; the stand-in itself never takes damage or gets pushed. */
@Mixin(LivingEntity.class)
abstract class ProxyMixin {
	@Inject(method = "hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("HEAD"), cancellable = true)
	private void osl$proxyHurt(final ServerLevel level, final DamageSource source, final float amount, final CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (NpcWar.isProxy(self)) {
			NpcWar.onProxyHit(self, source, amount);
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "isPushable()Z", at = @At("HEAD"), cancellable = true)
	private void osl$proxyNotPushable(final CallbackInfoReturnable<Boolean> cir) {
		if (NpcWar.isProxy((LivingEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "pushEntities()V", at = @At("HEAD"), cancellable = true)
	private void osl$proxyNoPush(final CallbackInfo ci) {
		if (NpcWar.isProxy((LivingEntity) (Object) this)) {
			ci.cancel();
		}
	}
}
