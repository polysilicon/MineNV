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
 * The supply line, Minecraft's half (server thread):
 * - New Vegas junk arrives as items ("give", from the Salvage key),
 * - finished New Vegas quests drop a chest beside the player, once per world ("quest"),
 * - builds found around the player unlock Courier perks, reported to New Vegas ("perks").
 * State lives in the world folder as osl_state.json.
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

		Osl.LOG.info("economy: {} worldspaces, quests {}, builds {}", state.slots.size(), state.quests, state.builds);
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

	private static ServerPlayer player(final MinecraftServer s) {
		List<ServerPlayer> players = s.getPlayerList().getPlayers();
		return players.isEmpty() ? null : players.get(0);
	}

	/** Every server tick: the starter kit once, and the build scan every PERK_SYNC_SECONDS. */
	static void tick(final MinecraftServer s) {
		ServerPlayer player = player(s);
		if (player == null || state == null) {
			return;
		}

		if (!state.starter) {
			state.starter = true;
			Sheets.Chest kit = Sheets.CHESTS.get(Sheets.STARTER_CHEST);
			kit.items().forEach(item -> give(s, player, item));
			save();
			toast(kit.name() + " is in your Minecraft inventory.");
		}

		if (++tickCounter % (Sheets.PERK_SYNC_SECONDS * 20) != 0 || !Osl.active) {
			return;
		}

		ServerLevel level = s.overworld();
		BlockPos at = player.blockPosition();
		BuildDetector.BlockView view = (x, y, z) -> {
			BlockPos p = new BlockPos(x, y, z);
			return level.isLoaded(p) ? BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString() : null;
		};
		boolean changed = false;
		for (Sheets.Build build : Sheets.BUILDS) {
			if (!state.builds.contains(build.id()) && BuildDetector.found(build, view, at.getX(), at.getY(), at.getZ())) {
				state.builds.add(build.id());
				changed = true;
				Sheets.Perk perk = Sheets.PERKS.get(build.perk());
				Osl.LOG.info("build found: {} -> perk {}", build.id(), perk.id());
				toast("You built " + build.name() + ": " + perk.name() + " unlocked.");
			}
		}

		if (changed) {
			save();
			reportPerks();
		}
	}

	/** {"t":"perks","on":[...]}: every perk the world's builds have unlocked (New Vegas applies what's new). */
	public static void reportPerks() {
		State st = state;
		if (st == null) {
			return;
		}

		JsonArray on = new JsonArray();
		for (Sheets.Build build : Sheets.BUILDS) {
			if (st.builds.contains(build.id())) {
				on.add(build.perk());
			}
		}

		Osl.events.accept("{\"t\":\"perks\",\"on\":" + on + "}");
	}

	static void toast(final String text) {
		MinecraftServer s = server;
		if (s != null && Osl.takeover) {
			s.execute(() -> s.getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u00a76[Mojave]\u00a7r " + text))));
		}

		JsonArray a = new JsonArray();
		a.add(text);
		String quoted = a.toString();
		Osl.events.accept("{\"t\":\"toast\",\"text\":" + quoted.substring(1, quoted.length() - 1) + "}");
	}

	/** {"t":"say","text":...}: a line from New Vegas for the chat (its HUD is hidden in Minecraft mode). */
	public static void say(final String text) {
		MinecraftServer s = server;
		if (s != null) {
			s.execute(() -> s.getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u00a76[Mojave]\u00a7r " + text))));
		}
	}

	/** The worldspace New Vegas is in: its strip of the Minecraft world (assigned on first visit). */
	public static void worldspace(final int formId) {
		State st = state;
		if (st == null) {
			return;
		}

		String key = Integer.toHexString(formId);
		Integer slot = st.slots.get(key);
		if (slot == null) {
			slot = st.slots.size();
			st.slots.put(key, slot);
			MinecraftServer s = server;
			if (s != null) {
				s.execute(Economy::save);
			}
		}

		Space.setSlot(slot);
	}

	/** {"t":"give","items":[[item,count],...],"why":"..."} from the Salvage key. */
	public static void give(final List<Sheets.Stack> items, final String why) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerPlayer player = player(s);
			if (player == null) {
				return;
			}

			items.forEach(item -> give(s, player, item));
			if (!why.isEmpty()) {
				toast(why);
			}
		});
	}

	private static void give(final MinecraftServer s, final ServerPlayer player, final Sheets.Stack item) {
		WorldBridge.command(String.format(Locale.ROOT, "give %s %s %d", player.getStringUUID(), item.item(), item.count()));
	}

	/** {"t":"quest","id":...}: New Vegas finished a quest. Its chest lands once per world. */
	public static void questDone(final String id) {
		MinecraftServer s = server;
		Sheets.Quest quest = Sheets.QUESTS.get(id);
		if (s == null || quest == null) {
			return;
		}

		s.execute(() -> {
			ServerPlayer player = player(s);
			if (player == null || state == null || state.quests.contains(id)) {
				return;
			}

			BlockPos at = chestSpot(s.overworld(), player);
			if (at == null) {
				Osl.LOG.info("quest {}: no ground for the chest yet, will retry", id);
				return;
			}

			state.quests.add(id);
			save();
			placeChest(at, Sheets.CHESTS.get(quest.chest()));
			toast(quest.message());
		});
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

	/** Test hook (self-test): forget the world's rewards. */
	public static void resetForTest() {
		if (state != null) {
			state.quests.clear();
			state.builds.clear();
		}
	}
}
