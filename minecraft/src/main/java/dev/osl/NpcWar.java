package dev.osl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.osl.mixin.MobAccessor;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft vs New Vegas's people. New Vegas lists its living actors near the Courier ("npcs"); each gets an
 * invisible, AI-less villager stand-in here that follows it. Steve, his arrows and Minecraft's hostile mobs can hit
 * the stand-ins, and every hit goes to the real NPC ("npchit"). New Vegas damage to the Courier comes back as damage
 * to Steve ("hurt"). New Vegas spawn eggs (npc_eggs.json) place real New Vegas NPCs ("spawnnpc").
 * Based on universal-modder's MobWar (MIT).
 */
public final class NpcWar {
	public static final String PROXY_TAG = "nv_npc";
	public static final String EGG_KEY = "osl_npc";
	private static final double FOLLOW_RANGE = 48.0;
	/** Brain-driven hostiles pick targets from memories, not goals: they don't join. */
	private static final java.util.Set<String> BRAIN_MOBS = java.util.Set.of("piglin", "piglin_brute", "hoglin", "zoglin", "breeze", "creaking", "warden");

	private static final AtomicReference<double[]> npcs = new AtomicReference<>();
	private static volatile long npcsNanos;
	private static final ConcurrentLinkedQueue<Double> hurts = new ConcurrentLinkedQueue<>();

	// server thread only
	private static final Map<Long, Villager> proxies = new HashMap<>();
	private static final Map<Integer, Long> refOf = new HashMap<>();
	private static final Map<Long, Integer> missing = new HashMap<>();
	private static final Map<java.util.UUID, Vec3> deathSpot = new HashMap<>();
	private static final Map<java.util.UUID, Long> lastEgg = new HashMap<>();

	private NpcWar() {
	}

	public static boolean isProxy(final Entity e) {
		return e instanceof Villager && (e.isInvisible() || e.entityTags().contains(PROXY_TAG));
	}

	private static boolean fighter(final Entity e) {
		return e instanceof Mob && e instanceof Enemy && !isProxy(e) && !BRAIN_MOBS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
	}

	/** {"t":"npcs","a":[[ref,x,y,z],...]} flattened (link thread). */
	public static void npcs(final double[] flat) {
		npcs.set(flat);
		npcsNanos = System.nanoTime();
	}

	/** {"t":"hurt","d":newVegasDamage} (link thread). */
	public static void hurt(final double newVegasDamage) {
		hurts.add(newVegasDamage);
	}

	/** New hostile mobs hunt stand-ins; stand-ins left over from an earlier session go. */
	public static void onEntityLoad(final Entity e, final ServerLevel level) {
		if (e instanceof Villager v && v.entityTags().contains(PROXY_TAG) && !proxies.containsValue(v)) {
			v.discard();
			return;
		}

		if (!fighter(e)) {
			return;
		}

		Mob mob = (Mob) e;
		GoalSelector targets = ((MobAccessor) mob).osl$targetSelector();
		if (targets.getAvailableGoals().stream().noneMatch(w -> w.getGoal() instanceof ProxyTargetGoal)) {
			targets.addGoal(2, new ProxyTargetGoal(mob));
		}

		AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (range != null && range.getBaseValue() < FOLLOW_RANGE) {
			range.setBaseValue(FOLLOW_RANGE);
		}
	}

	/** A stand-in was hit (server thread; the stand-in itself takes nothing). */
	public static void onProxyHit(final LivingEntity proxy, final DamageSource source, final float amount) {
		Long ref = refOf.get(proxy.getId());
		Entity attacker = source.getEntity();
		// only blows from someone count: /kill, walls and falls don't reach New Vegas's people
		if (ref == null || attacker == null || amount <= 0.0F || !Float.isFinite(amount)) {
			return;
		}

		if (source.getDirectEntity() instanceof Projectile projectile) {
			projectile.discard(); // the arrow ends in the NPC it hit
		}

		String by = attacker instanceof Player ? "player" : BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).getPath();
		Osl.events.accept(String.format(Locale.ROOT, "{\"t\":\"npchit\",\"id\":%d,\"d\":%.2f,\"by\":\"%s\"}", ref, Math.min(amount, 100.0F), by));
	}

	static void tick(final MinecraftServer s) {
		ServerLevel level = s.overworld();
		syncProxies(level);
		for (Double d; (d = hurts.poll()) != null;) {
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				if (p.isAlive() && !p.isCreative() && !p.isSpectator()) {
					p.hurtServer(level, level.damageSources().generic(), (float) (d * Sheets.NV_TO_MC_DAMAGE));
				}
			}
		}
	}

	private static void syncProxies(final ServerLevel level) {
		if (!Osl.takeover || System.nanoTime() - npcsNanos > 1_500_000_000L) {
			if (!proxies.isEmpty()) {
				clear();
			}

			npcs.set(null);
			return;
		}

		double[] p = npcs.getAndSet(null);
		if (p == null) {
			return;
		}

		java.util.Set<Long> seen = new java.util.HashSet<>();
		int dx = Space.offsetX();
		for (int i = 0; i + 3 < p.length; i += 4) {
			long ref = (long) p[i];
			double x = p[i + 1] + dx, y = p[i + 2], z = p[i + 3];
			seen.add(ref);
			Villager v = proxies.get(ref);
			if (v == null || v.isRemoved()) {
				v = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
				if (v == null) {
					continue;
				}

				v.setInvisible(true);
				v.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, false, false), null);
				v.setNoAi(true);
				v.setNoGravity(true);
				v.setSilent(true);
				v.addTag(PROXY_TAG);
				v.snapTo(x, y, z, 0.0F, 0.0F);
				proxies.put(ref, v); // before adding: the load event mustn't take it for a stale one
				if (!level.addFreshEntity(v)) {
					proxies.remove(ref);
					continue;
				}

				refOf.put(v.getId(), ref);
			} else {
				v.setPos(x, y, z);
			}

			missing.remove(ref);
		}

		for (Iterator<Map.Entry<Long, Villager>> it = proxies.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Long, Villager> e = it.next();
			if (seen.contains(e.getKey())) {
				continue;
			}

			if (missing.merge(e.getKey(), 1, Integer::sum) > 3 || e.getValue().isRemoved()) {
				refOf.remove(e.getValue().getId());
				e.getValue().discard();
				missing.remove(e.getKey());
				it.remove();
			}
		}
	}

	static void clear() {
		proxies.values().forEach(Entity::discard);
		proxies.clear();
		refOf.clear();
		missing.clear();
	}

	// ---- spawn eggs ----

	/** The egg's npc_eggs id, or null for any other item. */
	static String eggKind(final ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}

		String kind = data.copyTag().getStringOr(EGG_KEY, "");
		return Sheets.NPC_EGGS.containsKey(kind) ? kind : null;
	}

	/** Fabric UseBlockCallback: a New Vegas egg places the NPC in New Vegas instead of a mob here. */
	static InteractionResult useEgg(final Player player, final net.minecraft.world.level.Level level, final net.minecraft.world.InteractionHand hand,
		final net.minecraft.world.phys.BlockHitResult hit) {
		BlockPos at = hit.getBlockPos().relative(hit.getDirection());
		return placeEgg(player, level, hand, new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
	}

	/** Fabric UseEntityCallback: an egg used on a mob (or an NPC's stand-in) places the NPC where it stands. */
	static InteractionResult useEggOnEntity(final Player player, final net.minecraft.world.level.Level level, final net.minecraft.world.InteractionHand hand,
		final Entity target) {
		return placeEgg(player, level, hand, target.position());
	}

	/** Fabric UseItemCallback: used in the air (New Vegas's ground isn't always a Minecraft block): where Steve looks. */
	static InteractionResult useEggInAir(final Player player, final net.minecraft.world.level.Level level, final net.minecraft.world.InteractionHand hand) {
		if (eggKind(player.getItemInHand(hand)) == null) {
			return InteractionResult.PASS;
		}

		net.minecraft.world.phys.HitResult hit = player.pick(24.0, 1.0F, false);
		Vec3 at;
		if (hit instanceof net.minecraft.world.phys.BlockHitResult b && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			BlockPos p = b.getBlockPos().relative(b.getDirection());
			at = new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
		} else {
			Vec3 look = player.getLookAngle();
			at = player.position().add(look.x * 4.0, 0.0, look.z * 4.0);
		}

		return placeEgg(player, level, hand, at);
	}

	private static InteractionResult placeEgg(final Player player, final net.minecraft.world.level.Level level, final net.minecraft.world.InteractionHand hand,
		final Vec3 at) {
		ItemStack stack = player.getItemInHand(hand);
		String kind = eggKind(stack);
		if (kind == null) {
			return InteractionResult.PASS;
		}

		if (!level.isClientSide()) {
			if (!Osl.takeover) {
				player.sendSystemMessage(Component.literal("\u00a76[Mojave]\u00a7r New Vegas spawn eggs work in Minecraft mode."));
				return InteractionResult.SUCCESS;
			}

			// holding the button repeats a use every 4 ticks: one NPC per click
			long now = level.getGameTime();
			Long last = lastEgg.put(player.getUUID(), now);
			if (last != null && now - last < 10) {
				return InteractionResult.SUCCESS;
			}

			Osl.events.accept(String.format(Locale.ROOT, "{\"t\":\"spawnnpc\",\"kind\":\"%s\",\"p\":[%.3f,%.3f,%.3f]}",
				kind, at.x - Space.offsetX(), at.y, at.z));
			if (!player.getAbilities().instabuild) {
				stack.shrink(1);
			}
		}

		return InteractionResult.SUCCESS;
	}

	/** The give command's item text for an egg. */
	static String eggItem(final Sheets.NpcEgg egg) {
		return egg.eggItem() + "[custom_name=\"" + egg.name() + "\",custom_data={" + EGG_KEY + ":\"" + egg.id() + "\"}]";
	}

	/** /mojave egg <kind> [count] and /mojave eggs (one of each). */
	static void registerCommands(final com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> d) {
		d.register(Commands.literal("mojave")
			.then(Commands.literal("eggs").executes(c -> {
				ServerPlayer p = c.getSource().getPlayerOrException();
				Sheets.NPC_EGGS.values().forEach(egg -> WorldBridge.command("execute as " + p.getStringUUID() + " run give @s " + eggItem(egg) + " 4"));
				c.getSource().sendSuccess(() -> Component.literal("New Vegas spawn eggs: " + String.join(", ", Sheets.NPC_EGGS.keySet())), false);
				return 1;
			}))
			.then(Commands.literal("egg")
				.then(Commands.argument("kind", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(Sheets.NPC_EGGS.keySet(), b))
					.executes(c -> giveEgg(c.getSource(), StringArgumentType.getString(c, "kind"), 1))
					.then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
						.executes(c -> giveEgg(c.getSource(), StringArgumentType.getString(c, "kind"), IntegerArgumentType.getInteger(c, "count")))))));
	}

	private static int giveEgg(final net.minecraft.commands.CommandSourceStack source, final String kind, final int count)
		throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		Sheets.NpcEgg egg = Sheets.NPC_EGGS.get(kind);
		if (egg == null) {
			source.sendFailure(Component.literal("No New Vegas egg '" + kind + "'. Try: " + String.join(", ", Sheets.NPC_EGGS.keySet())));
			return 0;
		}

		ServerPlayer p = source.getPlayerOrException();
		WorldBridge.command("execute as " + p.getStringUUID() + " run give @s " + eggItem(egg) + " " + count);
		return 1;
	}

	// ---- respawn where Steve died (Minecraft's spawn point is somewhere else in the Mojave) ----

	static void onDeath(final ServerPlayer player) {
		deathSpot.put(player.getUUID(), player.position());
	}

	static void afterRespawn(final ServerPlayer player) {
		Vec3 at = deathSpot.remove(player.getUUID());
		if (at != null && Osl.takeover) {
			WorldBridge.command(String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f", player.getStringUUID(), at.x, at.y + 0.5, at.z));
		}
	}
}
