package dev.osl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The game connection between each player's Minecraft and the world: "osl:nv" carries what their New Vegas said
 * (the server-side rows of sheets/link.json), "osl:ev" what the world has for it. In single player this goes
 * through the integrated server's local connection, the same as for a friend in multiplayer.
 */
public final class Net {
	private static final int MAX = 1 << 20;

	/** Minecraft -> world: one message from this player's New Vegas (JSON). */
	public record Nv(String json) implements CustomPacketPayload {
		public static final Type<Nv> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Osl.ID, "nv"));
		static final StreamCodec<RegistryFriendlyByteBuf, Nv> CODEC = ByteBufCodecs.stringUtf8(MAX).map(Nv::new, Nv::json).cast();

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** World -> Minecraft: one message for this player's New Vegas, or for their Minecraft ("space", "mine"). */
	public record Ev(String json) implements CustomPacketPayload {
		public static final Type<Ev> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Osl.ID, "ev"));
		static final StreamCodec<RegistryFriendlyByteBuf, Ev> CODEC = ByteBufCodecs.stringUtf8(MAX).map(Ev::new, Ev::json).cast();

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private Net() {
	}

	static void register() {
		PayloadTypeRegistry.serverboundPlay().registerLarge(Nv.TYPE, Nv.CODEC, MAX);
		PayloadTypeRegistry.clientboundPlay().registerLarge(Ev.TYPE, Ev.CODEC, MAX);
		ServerPlayNetworking.registerGlobalReceiver(Nv.TYPE, (payload, context) -> {
			MinecraftServer s = context.server();
			ServerPlayer player = context.player();
			s.execute(() -> {
				try {
					handle(s, player, JsonParser.parseString(payload.json()).getAsJsonObject());
				} catch (RuntimeException e) {
					String m = payload.json();
					Osl.LOG.warn("bad message from {}'s New Vegas {}: {}", player.getPlainTextName(), m.length() > 200 ? m.substring(0, 200) : m, e.toString());
				}
			});
		});
	}

	/** Server thread. */
	private static void handle(final MinecraftServer s, final ServerPlayer player, final JsonObject m) {
		Courier c = Courier.of(player);
		switch (m.get("t").getAsString()) {
			case "hello" -> {
				// their New Vegas (re)connected: what it needs to know straight away
				c.sendSpace(s);
				Economy.reportPerks(s, c);
			}
			case "ws" -> Economy.worldspace(s, c, m.get("id").getAsInt());
			case "state" -> {
				c.active = m.get("active").getAsBoolean();
				c.takeover = m.get("takeover").getAsBoolean();
			}
			case "takeover" -> takeover(s, player, c, m);
			case "ground" -> WorldBridge.solid(s, player, c, ints(m.getAsJsonArray("c")));
			case "clear" -> WorldBridge.clearSolid(s, player);
			case "blocksync" -> WorldBridge.sync(s, player, c, m.has("r") ? Math.clamp(m.get("r").getAsInt(), 1, 64) : 32);
			case "give" -> Economy.give(s, player, c, stacks(m.getAsJsonArray("items")), m.has("why") ? m.get("why").getAsString() : "");
			case "quest" -> Economy.questDone(s, player, c, m.get("id").getAsString());
			case "say" -> Economy.say(player, m.get("text").getAsString());
			case "npcs" -> NpcWar.npcs(c, m.getAsJsonArray("a"));
			case "hurt" -> NpcWar.hurt(c, m.get("d").getAsDouble());
			case "devreset" -> {
				if (Boolean.getBoolean("osl.allowCommands")) {
					Economy.resetForTest(c);
				}
			}
			case "cmd" -> {
				// development only (tests and the fake host): disabled unless the server runs with -Dosl.allowCommands=true
				if (Boolean.getBoolean("osl.allowCommands")) {
					WorldBridge.command(m.get("c").getAsString());
				}
			}
			default -> Osl.LOG.warn("unknown message from {}'s New Vegas: {}", player.getPlainTextName(), m.get("t"));
		}
	}

	/** {"t":"takeover","on":bool,"feet":[x,y,z],"yaw":deg}: Minecraft mode on or off; on starts at the Courier's feet (worldspace coordinates). */
	private static void takeover(final MinecraftServer s, final ServerPlayer player, final Courier c, final JsonObject m) {
		boolean on = m.get("on").getAsBoolean();
		c.takeover = on;
		if (on) {
			player.getAbilities().flying = false;
			player.getAbilities().mayfly = false;
			player.onUpdateAbilities();
			if (m.has("feet")) {
				JsonArray f = m.getAsJsonArray("feet");
				float yaw = m.has("yaw") ? m.get("yaw").getAsFloat() : player.getYRot();
				WorldBridge.command(String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f %.2f 0", player.getStringUUID(),
					f.get(0).getAsDouble() + c.offsetX, f.get(1).getAsDouble() + 0.05, f.get(2).getAsDouble(), yaw));
			}
		} else {
			NpcWar.clear(c);
		}
	}

	private static int[] ints(final JsonArray a) {
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsInt();
		}

		return out;
	}

	private static List<Sheets.Stack> stacks(final JsonArray a) {
		List<Sheets.Stack> out = new ArrayList<>();
		for (int i = 0; i < a.size(); i++) {
			JsonArray e = a.get(i).getAsJsonArray();
			String item = e.get(0).getAsString();
			// only plain item ids and the component syntax the sheets use; never a command
			if (!item.matches("[a-z0-9_:.\\-/]+(\\[[^\\]\\n]*\\])?")) {
				throw new IllegalArgumentException("bad item " + item);
			}

			out.add(new Sheets.Stack(item, Math.clamp(e.get(1).getAsInt(), 1, 64 * 36)));
		}

		return out;
	}
}
