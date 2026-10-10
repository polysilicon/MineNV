package dev.osl;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * One player and their own New Vegas (server thread). Every player runs their own New Vegas next to their own
 * Minecraft; their Minecraft relays what that New Vegas says over the game connection (Net), and what the world has
 * for that New Vegas goes back the same way. Single player is the same with one Courier.
 */
public final class Courier {
	private static final Map<UUID, Courier> ALL = new ConcurrentHashMap<>();

	public final UUID id;
	/** Their New Vegas is driving the camera (Osl.active on their side). */
	volatile boolean active;
	/** Minecraft mode on their side. */
	volatile boolean takeover;
	/** Their worldspace's strip of the Minecraft world (Space). */
	volatile int offsetX;
	volatile int worldspace = Integer.MIN_VALUE;

	private Courier(final UUID id) {
		this.id = id;
	}

	public static Courier of(final ServerPlayer player) {
		return ALL.computeIfAbsent(player.getUUID(), Courier::new);
	}

	/** Null for a player whose Minecraft hasn't said anything yet (or no player). */
	public static Courier find(final UUID id) {
		return ALL.get(id);
	}

	static Collection<Courier> all() {
		return ALL.values();
	}

	static void remove(final UUID id) {
		ALL.remove(id);
	}

	static void clear() {
		ALL.clear();
	}

	public boolean active() {
		return active;
	}

	public boolean takeover() {
		return takeover;
	}

	public int offsetX() {
		return offsetX;
	}

	/** True for an x inside the strip of the worldspace this player's New Vegas is in now. */
	boolean inStrip(final int x) {
		return Math.abs(x - offsetX) < Sheets.WORLDSPACE_SPACING / 2;
	}

	ServerPlayer player(final MinecraftServer s) {
		return s == null ? null : s.getPlayerList().getPlayer(id);
	}

	/** A message for this player's New Vegas (a row of sheets/link.json), through their Minecraft. */
	void send(final MinecraftServer s, final String json) {
		ServerPlayer p = player(s);
		if (p != null && ServerPlayNetworking.canSend(p, Net.Ev.TYPE)) {
			ServerPlayNetworking.send(p, new Net.Ev(json));
		}
	}

	void sendSpace(final MinecraftServer s) {
		send(s, String.format(Locale.ROOT, "{\"t\":\"space\",\"dx\":%d}", offsetX));
	}
}
