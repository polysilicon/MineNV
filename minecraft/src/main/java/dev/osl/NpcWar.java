package dev.osl;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.osl.mixin.MobAccessor;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.google.gson.JsonArray;
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
 * Minecraft vs New Vegas's people. Each player's New Vegas lists its living actors near their Courier ("npcs");
 * each gets an AI-less villager stand-in here that follows it, named after the NPC. Its owner's Minecraft hides it
 * (their New Vegas draws the real NPC there); everyone else sees the stand-in. Steve, his friends, arrows and
 * Minecraft's hostile mobs can hit the stand-ins, and every hit goes to the real NPC in its owner's New Vegas
 * ("npchit"). New Vegas damage to a Courier comes back as damage to that player's Steve ("hurt"). New Vegas spawn
 * eggs (npc_eggs.json) place real NPCs in the user's New Vegas ("spawnnpc"). Based on universal-modder's MobWar (MIT).
 */
public final class NpcWar {
	public static final String PROXY_TAG = "nv_npc";
	public static final String EGG_KEY = "osl_npc";
	private static final double FOLLOW_RANGE = 48.0;
	private static final int MAX_PER_PLAYER = 64;
	/** Brain-driven hostiles pick targets from memories, not goals: they don't join. */
	private static final java.util.Set<String> BRAIN_MOBS = java.util.Set.of("piglin", "piglin_brute", "hoglin", "zoglin", "breeze", "creaking", "warden");
	/** Client side: entity ids of this player's own stand-ins ("mine" from the world), hidden in their picture. */
	public static final java.util.Set<Integer> HIDDEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** One New Vegas NPC's stand-in. */
	private record Proxy(UUID owner, long ref, Villager entity) {
	}

	/** One player's latest actor list, kept until the next server tick. */
	private record Seen(JsonArray list, long nanos) {
	}

	// server thread only
	private static final Map<UUID, Seen> seen = new HashMap<>();
	private static final Map<UUID, Long> lastSeen = new HashMap<>();
	private static final Map<UUID, Map<Long, Proxy>> proxies = new HashMap<>();
	private static final Map<Integer, Proxy> byEntity = new HashMap<>();
	private static final Map<UUID, Map<Long, Integer>> missing = new HashMap<>();
	private static final Map<UUID, java.util.List<Double>> hurts = new HashMap<>();
	private static final Map<UUID, Vec3> deathSpot = new HashMap<>();
	private static final Map<UUID, Long> lastEgg = new HashMap<>();

	private NpcWar() {
	}

	/** Both sides: stand-ins are AI-less floating villagers (that much is synced to every client), tagged on the server. */
	public static boolean isProxy(final Entity e) {
		return e instanceof Villager v && v.isNoAi() && v.isNoGravity() && (v.level().isClientSide() || v.entityTags().contains(PROXY_TAG));
	}

	private static boolean fighter(final Entity e) {
		return e instanceof Mob && e instanceof Enemy && !isProxy(e) && !BRAIN_MOBS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
	}

	/** {"t":"npcs","a":[[ref,x,y,z,"name"?],...]} from a player's New Vegas (worldspace coordinates, feet, blocks). */
	static void npcs(final Courier c, final JsonArray list) {
		seen.put(c.id, new Seen(list, System.nanoTime()));
		lastSeen.put(c.id, System.nanoTime());
	}

	/** {"t":"hurt","d":newVegasDamage}: New Vegas hurt this player's Courier. */
	static void hurt(final Courier c, final double newVegasDamage) {
		hurts.computeIfAbsent(c.id, k -> new java.util.ArrayList<>()).add(newVegasDamage);
	}

	/** New hostile mobs hunt stand-ins; stand-ins left over from an earlier session go. */
	public static void onEntityLoad(final Entity e, final ServerLevel level) {
		if (e instanceof Villager v && v.entityTags().contains(PROXY_TAG) && !byEntity.containsKey(v.getId())) {
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
		Proxy p = byEntity.get(proxy.getId());
		Entity attacker = source.getEntity();
		// only blows from someone count: /kill, walls and falls don't reach New Vegas's people
		if (p == null || attacker == null || amount <= 0.0F || !Float.isFinite(amount)) {
			return;
		}

		if (source.getDirectEntity() instanceof Projectile projectile) {
			projectile.discard(); // the arrow ends in the NPC it hit
		}

		Courier owner = Courier.find(p.owner());
		if (owner == null) {
			return;
		}

		// "player": the NPC's own Courier hit it (it turns on them); a friend's Steve counts like a Minecraft mob
		String by = attacker instanceof Player ? (attacker.getUUID().equals(p.owner()) ? "player" : "friend")
			: BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).getPath();
		owner.send(proxy.level().getServer(), String.format(Locale.ROOT, "{\"t\":\"npchit\",\"id\":%d,\"d\":%.2f,\"by\":\"%s\"}",
			p.ref(), Math.min(amount, 100.0F), by));
	}

	static void tick(final MinecraftServer s) {
		ServerLevel level = s.overworld();
		for (Courier c : Courier.all()) {
			syncProxies(s, level, c);
		}

		for (Map.Entry<UUID, java.util.List<Double>> e : hurts.entrySet()) {
			ServerPlayer p = s.getPlayerList().getPlayer(e.getKey());
			for (double d : e.getValue()) {
				if (p != null && p.isAlive() && !p.isCreative() && !p.isSpectator()) {
					p.hurtServer(level, level.damageSources().generic(), (float) (d * Sheets.NV_TO_MC_DAMAGE));
				}
			}
		}

		hurts.clear();
	}

	private static void syncProxies(final MinecraftServer s, final ServerLevel level, final Courier c) {
		Long last = lastSeen.get(c.id);
		if (!c.takeover || last == null || System.nanoTime() - last > 1_500_000_000L || c.player(s) == null) {
			clear(c);
			seen.remove(c.id);
			return;
		}

		Seen now = seen.remove(c.id);
		if (now == null) {
			return;
		}

		Map<Long, Proxy> mine = proxies.computeIfAbsent(c.id, k -> new HashMap<>());
		Map<Long, Integer> gone = missing.computeIfAbsent(c.id, k -> new HashMap<>());
		java.util.Set<Long> here = new java.util.HashSet<>();
		boolean changed = false;
		for (int i = 0; i < now.list().size() && i < MAX_PER_PLAYER; i++) {
			JsonArray a = now.list().get(i).getAsJsonArray();
			long ref = a.get(0).getAsLong();
			double x = a.get(1).getAsDouble() + c.offsetX, y = a.get(2).getAsDouble(), z = a.get(3).getAsDouble();
			String name = a.size() > 4 && !a.get(4).isJsonNull() ? a.get(4).getAsString() : "";
			here.add(ref);
			gone.remove(ref);
			Proxy p = mine.get(ref);
			if (p != null && !p.entity().isRemoved()) {
				p.entity().setPos(x, y, z);
				continue;
			}

			Villager v = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (v == null) {
				continue;
			}

			v.setNoAi(true);
			v.setNoGravity(true);
			v.setSilent(true);
			v.addTag(PROXY_TAG);
			if (!name.isBlank()) {
				v.setCustomName(Component.literal(name.length() > 40 ? name.substring(0, 40) : name));
				v.setCustomNameVisible(true);
			}

			v.snapTo(x, y, z, 0.0F, 0.0F);
			p = new Proxy(c.id, ref, v);
			byEntity.put(v.getId(), p); // before adding: the load event mustn't take it for a stale one
			if (!level.addFreshEntity(v)) {
				byEntity.remove(v.getId());
				continue;
			}

			mine.put(ref, p);
			changed = true;
		}

		for (Iterator<Map.Entry<Long, Proxy>> it = mine.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Long, Proxy> e = it.next();
			if (here.contains(e.getKey()) && !e.getValue().entity().isRemoved()) {
				continue;
			}

			if (gone.merge(e.getKey(), 1, Integer::sum) > 3 || e.getValue().entity().isRemoved()) {
				byEntity.remove(e.getValue().entity().getId());
				e.getValue().entity().discard();
				gone.remove(e.getKey());
				it.remove();
				changed = true;
			}
		}

		if (changed) {
			sendMine(s, c, mine);
		}
	}

	/** {"t":"mine","ids":[...]}: the owner's Minecraft hides its own stand-ins (their New Vegas draws the NPCs). */
	private static void sendMine(final MinecraftServer s, final Courier c, final Map<Long, Proxy> mine) {
		StringBuilder ids = new StringBuilder();
		for (Proxy p : mine.values()) {
			ids.append(ids.isEmpty() ? "" : ",").append(p.entity().getId());
		}

		c.send(s, "{\"t\":\"mine\",\"ids\":[" + ids + "]}");
	}

	/** This player's stand-ins go (Minecraft mode off, their New Vegas quiet, or they left). */
	static void clear(final Courier c) {
		Map<Long, Proxy> mine = proxies.remove(c.id);
		missing.remove(c.id);
		if (mine == null) {
			return;
		}

		for (Proxy p : mine.values()) {
			byEntity.remove(p.entity().getId());
			p.entity().discard();
		}
	}

	static void clear() {
		Courier.all().forEach(NpcWar::clear);
		proxies.clear();
		byEntity.clear();
		seen.clear();
		lastSeen.clear();
		hurts.clear();
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

		if (!level.isClientSide() && player instanceof ServerPlayer sp) {
			Courier c = Courier.of(sp);
			if (!c.takeover) {
				player.sendSystemMessage(Component.literal("\u00a76[Mojave]\u00a7r New Vegas spawn eggs work in Minecraft mode."));
				return InteractionResult.SUCCESS;
			}

			// holding the button repeats a use every 4 ticks: one NPC per click
			long now = level.getGameTime();
			Long last = lastEgg.put(player.getUUID(), now);
			if (last != null && now - last < 10) {
				return InteractionResult.SUCCESS;
			}

			c.send(sp.level().getServer(), String.format(Locale.ROOT, "{\"t\":\"spawnnpc\",\"kind\":\"%s\",\"p\":[%.3f,%.3f,%.3f]}",
				kind, at.x - c.offsetX, at.y, at.z));
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
		Courier c = Courier.find(player.getUUID());
		if (at != null && c != null && c.takeover) {
			WorldBridge.command(String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f", player.getStringUUID(), at.x, at.y + 0.5, at.z));
		}
	}
}
