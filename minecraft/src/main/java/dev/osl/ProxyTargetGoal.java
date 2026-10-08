package dev.osl;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.npc.villager.Villager;

/** Hostile mobs hunt New Vegas NPCs' stand-ins (from universal-modder's MobWar, MIT). */
final class ProxyTargetGoal extends NearestAttackableTargetGoal<Villager> {
	ProxyTargetGoal(final Mob mob) {
		super(mob, Villager.class, 10, false, false, (target, level) -> NpcWar.isProxy(target));
		this.targetConditions.ignoreInvisibilityTesting().ignoreLineOfSight();
	}
}
