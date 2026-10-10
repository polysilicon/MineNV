package dev.osl.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.osl.Osl;
import dev.osl.Sheets;
import java.net.InetSocketAddress;
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
		Relay.toWorld("{\"t\":\"hello\"}");
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
				// for the world (ours or a friend's): it keeps each player's ground, rewards and NPC stand-ins
				case "ground", "clear", "blocksync", "give", "quest", "say", "npcs", "hurt", "devreset", "cmd" -> Relay.toWorld(message);
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
}
