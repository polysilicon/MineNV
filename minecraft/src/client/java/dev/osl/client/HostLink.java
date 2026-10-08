package dev.osl.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.osl.Economy;
import dev.osl.Osl;
import dev.osl.Sheets;
import dev.osl.Space;
import dev.osl.WorldBridge;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * New Vegas's connection: a WebSocket server on 127.0.0.1:LINK_PORT (or -Dosl.port). The messages are the rows of
 * sheets/link.json.
 */
public final class HostLink extends WebSocketServer {
	private static HostLink instance;
	private static volatile boolean everConnected;
	private static volatile long lastSeenNanos = System.nanoTime();

	private HostLink(final int port) {
		super(new InetSocketAddress("127.0.0.1", port));
		this.setReuseAddr(true);
		this.setDaemon(true);
	}

	static void launch() {
		int port = Integer.getInteger("osl.port", Sheets.LINK_PORT);
		instance = new HostLink(port);
		instance.start();
		Osl.events = message -> instance.broadcast(message);
	}

	static int connections() {
		HostLink link = instance;
		return link == null ? 0 : link.getConnections().size();
	}

	static boolean everConnected() {
		return everConnected;
	}

	static long lastSeenNanos() {
		return lastSeenNanos;
	}

	@Override
	public void onStart() {
		// fixed words: the launcher and tests wait for this line in latest.log
		Osl.LOG.info("OSL link listening on 127.0.0.1:{}", this.getPort());
	}

	@Override
	public void onOpen(final WebSocket conn, final ClientHandshake handshake) {
		Osl.LOG.info("New Vegas connected from {}", conn.getRemoteSocketAddress());
		everConnected = true;
		lastSeenNanos = System.nanoTime();
		conn.send(String.format(Locale.ROOT, "{\"t\":\"hello\",\"v\":1,\"shm\":\"%s\",\"pid\":%d}",
			FrameExporter.NAME.replace("\\", "\\\\"), ProcessHandle.current().pid()));
		Economy.reportPerks();
	}

	@Override
	public void onClose(final WebSocket conn, final int code, final String reason, final boolean remote) {
		Osl.LOG.info("New Vegas disconnected ({} {})", code, reason);
		if (Osl.takeover) {
			// New Vegas went away mid-game: stop playing by ourselves, let go of every key
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.execute(() -> {
				Osl.takeover = false;
				InputBridge.releaseAll(minecraft);
			});
		}
		lastSeenNanos = System.nanoTime();
	}

	@Override
	public void onMessage(final WebSocket conn, final String message) {
		try {
			JsonObject m = JsonParser.parseString(message).getAsJsonObject();
			switch (m.get("t").getAsString()) {
				case "cam" -> HostState.update(m);
				case "takeover", "in", "mv", "btn", "wheel", "txt" -> {
					Minecraft minecraft = Minecraft.getInstance();
					minecraft.execute(() -> ClientInput.takeover(minecraft, m));
				}
				case "ground" -> WorldBridge.solid(columns(m.getAsJsonArray("c")));
				case "clear" -> WorldBridge.clearSolid();
				case "blocksync" -> WorldBridge.sync(m.has("r") ? m.get("r").getAsInt() : 32);
				case "give" -> Economy.give(stacks(m.getAsJsonArray("items")), m.has("why") ? m.get("why").getAsString() : "");
				case "quest" -> Economy.questDone(m.get("id").getAsString());
				case "say" -> Economy.say(m.get("text").getAsString());
				case "npcs" -> {
					JsonArray list = m.getAsJsonArray("a");
					double[] flat = new double[list.size() * 4];
					for (int i = 0; i < list.size(); i++) {
						JsonArray e = list.get(i).getAsJsonArray();
						for (int k = 0; k < 4; k++) {
							flat[i * 4 + k] = e.get(k).getAsDouble();
						}
					}

					dev.osl.NpcWar.npcs(flat);
				}
				case "hurt" -> dev.osl.NpcWar.hurt(m.get("d").getAsDouble());
				case "devreset" -> {
					if (Boolean.getBoolean("osl.allowCommands")) {
						Economy.resetForTest();
					}
				}
				case "cmd" -> {
					// development only (tests and the fake host): disabled unless -Dosl.allowCommands=true
					if (Boolean.getBoolean("osl.allowCommands")) {
						WorldBridge.command(m.get("c").getAsString());
					}
				}
				default -> {
					Minecraft minecraft = Minecraft.getInstance();
					minecraft.execute(() -> ClientInput.handle(minecraft, m));
				}
			}
		} catch (RuntimeException e) {
			Osl.LOG.warn("bad message from New Vegas {}: {}", message.length() > 200 ? message.substring(0, 200) : message, e.toString());
		}
	}

	@Override
	public void onError(final WebSocket conn, final Exception e) {
		Osl.LOG.warn("link error", e);
	}

	/** Ground columns arrive in the worldspace's own coordinates: shift x into its strip. */
	private static int[] columns(final JsonArray a) {
		int[] out = new int[a.size()];
		int dx = Space.offsetX();
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsInt() + (i % 4 == 0 ? dx : 0);
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
