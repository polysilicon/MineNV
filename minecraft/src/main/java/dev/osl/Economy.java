package dev.osl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The supply line, Minecraft's half (server thread), for each player and their own New Vegas:
 * - New Vegas junk arrives as items ("give", from the Salvage key),
 * - finished New Vegas quests drop a chest beside the player, once per player ("quest"),
 * - builds found around the player unlock Courier perks, reported to their New Vegas ("perks").
 * State lives in the world folder as osl_state.json: worldspace strips are the world's, rewards each player's.
 */
public final class Economy {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static volatile MinecraftServer server;
	private static State state;
	private static Path file;
	private static int tickCounter;

	/** What the world remembers. */
	static final class State {
		Map<String, Integer> slots = new LinkedHashMap<>();
		Map<String, Rewards> players = new LinkedHashMap<>();
		// before 0.4.0 (one player): moved to the first player who plays the world, its owner
		Set<String> quests;
		Set<String> builds;
		Boolean starter;
	}

	/** One player's rewards. */
	static final class Rewards {
		Set<String> quests = new LinkedHashSet<>();
		Set<String> builds = new LinkedHashSet<>();
		boolean starter;
	}

	private Economy() {
	}

	static void attach(final MinecraftServer s) {
		server = s;
		file = s.getWorldPath(LevelResource.ROOT).resolve("osl_state.json");
		state = new State();
		if (Files.exists(file)) {
			try {
				State loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
				if (loaded != null) {
					state = loaded;
				}
			} catch (IOException | RuntimeException e) {
				Osl.LOG.warn("osl_state.json unreadable, starting fresh", e);
			}
		}

		if (state.slots == null) {
			state.slots = new LinkedHashMap<>();
		}

		if (state.players == null) {
			state.players = new LinkedHashMap<>();
		}

		Osl.LOG.info("economy: {} worldspaces, {} players", state.slots.size(), state.players.size());
	}

	static void detach() {
		save();
		server = null;
	}

	private static void save() {
		if (file == null || state == null) {
			return;
		}

		try {
			Files.writeString(file, GSON.toJson(state), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Osl.LOG.warn("couldn't save osl_state.json", e);
		}
	}

	private static Rewards rewards(final Courier c) {
		Rewards r = state.players.get(c.id.toString());
		if (r == null) {
			r = new Rewards();
			if (state.quests != null || state.builds != null || state.starter != null) {
				// a world from before multiplayer: its rewards were its owner's, the first to play it now
				if (state.quests != null) {
					r.quests.addAll(state.quests);
				}

				if (state.builds != null) {
					r.builds.addAll(state.builds);
				}

				r.starter = Boolean.TRUE.equals(state.starter);
				state.quests = null;
				state.builds = null;
				state.starter = null;
			}

			state.players.put(c.id.toString(), r);
			save();
		}

		return r;
	}

	/** Every server tick: each player's starter kit once, and the build scan every PERK_SYNC_SECONDS. */
	static void tick(final MinecraftServer s) {
		if (state == null) {
			return;
		}

		boolean scan = ++tickCounter % (Sheets.PERK_SYNC_SECONDS * 20) == 0;
		for (ServerPlayer player : s.getPlayerList().getPlayers()) {
			Courier c = Courier.find(player.getUUID());
			if (c == null) {
				continue; // not a Mojavecraft player (or their Minecraft hasn't spoken yet)
			}

			Rewards r = rewards(c);
			if (!r.starter) {
				r.starter = true;
				Sheets.Chest kit = Sheets.CHESTS.get(Sheets.STARTER_CHEST);
				kit.items().forEach(item -> give(player, item));
				save();
				toast(s, c, kit.name() + " is in your Minecraft inventory.");
			}

			if (scan && c.active) {
				scanBuilds(s, player, c, r);
			}
		}
	}

	private static void scanBuilds(final MinecraftServer s, final ServerPlayer player, final Courier c, final Rewards r) {
		ServerLevel level = s.overworld();
		BlockPos at = player.blockPosition();
		BuildDetector.BlockView view = (x, y, z) -> {
			BlockPos p = new BlockPos(x, y, z);
			return level.isLoaded(p) ? BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString() : null;
		};
		boolean changed = false;
		for (Sheets.Build build : Sheets.BUILDS) {
			if (!r.builds.contains(build.id()) && BuildDetector.found(build, view, at.getX(), at.getY(), at.getZ())) {
				r.builds.add(build.id());
				changed = true;
				Sheets.Perk perk = Sheets.PERKS.get(build.perk());
				Osl.LOG.info("build found by {}: {} -> perk {}", player.getPlainTextName(), build.id(), perk.id());
				toast(s, c, "You built " + build.name() + ": " + perk.name() + " unlocked.");
			}
		}

		if (changed) {
			save();
			reportPerks(s, c);
		}
	}

	/** {"t":"perks","on":[...]}: every perk this player's builds have unlocked (their New Vegas applies what's new). */
	static void reportPerks(final MinecraftServer s, final Courier c) {
		if (state == null) {
			return;
		}

		Rewards r = rewards(c);
		JsonArray on = new JsonArray();
		for (Sheets.Build build : Sheets.BUILDS) {
			if (r.builds.contains(build.id())) {
				on.add(build.perk());
			}
		}

		c.send(s, "{\"t\":\"perks\",\"on\":" + on + "}");
	}

	static void toast(final MinecraftServer s, final Courier c, final String text) {
		ServerPlayer p = c.player(s);
		if (p != null && c.takeover) {
			p.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u00a76[Mojave]\u00a7r " + text));
		}

		JsonArray a = new JsonArray();
		a.add(text);
		String quoted = a.toString();
		c.send(s, "{\"t\":\"toast\",\"text\":" + quoted.substring(1, quoted.length() - 1) + "}");
	}

	/** {"t":"say","text":...}: a line from their New Vegas for their chat (its HUD is hidden in Minecraft mode). */
	static void say(final ServerPlayer player, final String text) {
		player.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u00a76[Mojave]\u00a7r " + text));
	}

	/** The worldspace their New Vegas is in: its strip of the Minecraft world (assigned on first visit, the same for everyone). */
	static void worldspace(final MinecraftServer s, final Courier c, final int formId) {
		if (state == null) {
			return;
		}

		String key = Integer.toHexString(formId);
		Integer slot = state.slots.get(key);
		if (slot == null) {
			slot = state.slots.size();
			state.slots.put(key, slot);
			save();
		}

		c.worldspace = formId;
		c.offsetX = slot * Sheets.WORLDSPACE_SPACING;
		c.sendSpace(s);
	}

	/** {"t":"give","items":[[item,count],...],"why":"..."} from the Salvage key. */
	static void give(final MinecraftServer s, final ServerPlayer player, final Courier c, final List<Sheets.Stack> items, final String why) {
		items.forEach(item -> give(player, item));
		if (!why.isEmpty()) {
			toast(s, c, why);
		}
	}

	private static void give(final ServerPlayer player, final Sheets.Stack item) {
		// `give <uuid>` gives nothing in 26.3; `execute as <uuid>` finds the player
		WorldBridge.command(String.format(Locale.ROOT, "execute as %s run give @s %s %d", player.getStringUUID(), item.item(), item.count()));
	}

	/** {"t":"quest","id":...}: their New Vegas finished a quest. Its chest lands once per player. */
	static void questDone(final MinecraftServer s, final ServerPlayer player, final Courier c, final String id) {
		Sheets.Quest quest = Sheets.QUESTS.get(id);
		if (quest == null || state == null) {
			return;
		}

		Rewards r = rewards(c);
		if (r.quests.contains(id)) {
			return;
		}

		BlockPos at = chestSpot(s.overworld(), player);
		if (at == null) {
			Osl.LOG.info("quest {}: no ground for the chest yet, will retry", id);
			return;
		}

		r.quests.add(id);
		save();
		placeChest(at, Sheets.CHESTS.get(quest.chest()));
		toast(s, c, quest.message());
	}

	/**
	 * CHEST_DISTANCE blocks in front of the player, standing on the ground (New Vegas's ground or a player block,
	 * never another chest); the nearest free spot around it when that one is taken.
	 */
	static BlockPos chestSpot(final ServerLevel level, final ServerPlayer player) {
		BlockPos base = player.blockPosition().relative(player.getDirection(), Sheets.CHEST_DISTANCE);
		for (int ring = 0; ring <= 3; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}

					for (int dy = 3; dy >= -6; dy--) {
						BlockPos p = base.offset(dx, dy, dz);
						if (!level.isLoaded(p) || !level.getBlockState(p).isAir()) {
							continue;
						}

						var below = level.getBlockState(p.below());
						if (!below.isAir()) {
							if (below.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
								break;
							}

							return p;
						}
					}
				}
			}
		}

		return null;
	}

	static void placeChest(final BlockPos at, final Sheets.Chest chest) {
		JsonArray name = new JsonArray();
		name.add(chest.name());
		String quoted = name.toString();
		WorldBridge.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest{CustomName:%s} replace",
			at.getX(), at.getY(), at.getZ(), quoted.substring(1, quoted.length() - 1)));
		List<Sheets.Stack> items = new ArrayList<>(chest.items());
		for (int i = 0; i < items.size() && i < 27; i++) {
			Sheets.Stack item = items.get(i);
			WorldBridge.command(String.format(Locale.ROOT, "item replace block %d %d %d container.%d with %s %d",
				at.getX(), at.getY(), at.getZ(), i, item.item(), item.count()));
		}
	}

	/** Test hook (self-test): forget this player's rewards. */
	static void resetForTest(final Courier c) {
		if (state != null) {
			Rewards r = rewards(c);
			r.quests.clear();
			r.builds.clear();
		}
	}
}
