package dev.osl.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.osl.Net;
import dev.osl.NpcWar;
import dev.osl.Osl;
import dev.osl.Space;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * This player's Minecraft between their New Vegas and the world (their own, or a friend's): New Vegas's messages
 * for the world go up the game connection ("osl:nv"), the world's messages for New Vegas come back down ("osl:ev")
 * and go out over the link. Until the player is in a world, important messages wait (salvage, quests).
 */
public final class Relay {
	private static final int MAX_WAITING = 200;
	/** Client thread only. */
	private static final Deque<String> waiting = new ArrayDeque<>();
	private static boolean lastActive, lastTakeover;
	private static int stateTicks;

	private Relay() {
	}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(Net.Ev.TYPE, (payload, context) -> fromWorld(payload.json()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> minecraft.execute(() -> joined(minecraft)));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(NpcWar.HIDDEN::clear));
	}

	/** Any thread: a message from New Vegas for the world. */
	static void toWorld(final String json) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			if (ClientPlayNetworking.canSend(Net.Nv.TYPE)) {
				ClientPlayNetworking.send(new Net.Nv(json));
			} else if (keep(json)) {
				if (waiting.size() >= MAX_WAITING) {
					waiting.removeFirst();
				}

				waiting.addLast(json);
			}
		});
	}

	/** Positions and ground are sent again anyway; what New Vegas gave up (junk, a finished quest) mustn't be lost. */
	private static boolean keep(final String json) {
		return json.contains("\"give\"") || json.contains("\"quest\"") || json.contains("\"ws\"") || json.contains("\"hello\"");
	}

	private static void joined(final Minecraft minecraft) {
		// fixed words: Melty's first-run setup and the launcher wait for this line in latest.log
		Osl.LOG.info("OSL ready: in {}", minecraft.isLocalServer() ? "our own world" : "a friend's world");
		lastActive = !Osl.active; // forces a "state" now
		sendState();
		if (OslClient.linked()) {
			toWorld("{\"t\":\"hello\"}");
		}

		HostState.resendWorldspace();
		while (!waiting.isEmpty() && ClientPlayNetworking.canSend(Net.Nv.TYPE)) {
			ClientPlayNetworking.send(new Net.Nv(waiting.removeFirst()));
		}
	}

	/** Client tick: the world hears when New Vegas starts or stops driving, and Minecraft mode on or off. */
	static void tick() {
		if (++stateTicks % 20 == 0 || Osl.active != lastActive || Osl.takeover != lastTakeover) {
			sendState();
		}
	}

	private static void sendState() {
		if (!ClientPlayNetworking.canSend(Net.Nv.TYPE)) {
			return;
		}

		lastActive = Osl.active;
		lastTakeover = Osl.takeover;
		ClientPlayNetworking.send(new Net.Nv(String.format(Locale.ROOT, "{\"t\":\"state\",\"active\":%b,\"takeover\":%b}", lastActive, lastTakeover)));
	}

	/** Client thread: a message from the world for this player's New Vegas (or for this Minecraft). */
	private static void fromWorld(final String json) {
		try {
			JsonObject m = JsonParser.parseString(json).getAsJsonObject();
			switch (m.get("t").getAsString()) {
				case "space" -> {
					Space.set(m.get("dx").getAsInt());
					Osl.LOG.info("worldspace strip offset {}", Space.offsetX());
				}
				case "mine" -> {
					JsonArray ids = m.getAsJsonArray("ids");
					NpcWar.HIDDEN.clear();
					ids.forEach(e -> NpcWar.HIDDEN.add(e.getAsInt()));
				}
				default -> Osl.events.accept(json);
			}
		} catch (RuntimeException e) {
			Osl.LOG.warn("bad message from the world {}: {}", json.length() > 200 ? json.substring(0, 200) : json, e.toString());
		}
	}
}
