package dev.osl.client;

import dev.osl.Osl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;

/**
 * Playing together (Melty's multiplayer). The host's own world opens to friends as soon as it's loaded: e4mc
 * (bundled) gives it an internet address like abc-def.e4mc.link, which goes into latest.log for Melty
 * ("this world is open to friends at ..."). A friend's Play has Melty write that address into
 * config/mojavecraft.properties (join=...), and their Minecraft joins it instead of opening its own world.
 * Approach from CyberCraft (MIT).
 */
public final class Friends {
	private static final Pattern E4MC_LINK = Pattern.compile("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.e4mc\\.link", Pattern.CASE_INSENSITIVE);
	/** The address we're joining (or joined), null in our own world. Client thread. */
	private static String joining;
	/** The join address didn't work this session: open our own world from now on. */
	private static boolean joinFailed;
	private static boolean published;
	private static String link;
	private static String note;

	private Friends() {
	}

	static void register() {
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> minecraft.execute(() -> {
			if (minecraft.isLocalServer()) {
				open(minecraft);
			} else {
				note = "Playing in a friend's Mojave (" + joining + "). Your New Vegas, quests and caps stay your own.";
			}
		}));
	}

	private static Path config(final Minecraft minecraft) {
		return minecraft.gameDirectory.toPath().resolve("config").resolve("mojavecraft.properties");
	}

	/** join= in config/mojavecraft.properties (Melty writes it for a friend's Play and empties it for solo play). */
	private static String joinAddress(final Minecraft minecraft) {
		Path file = config(minecraft);
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, """
					# Mojavecraft. Melty fills this in when you join a friend: their world's address after join=
					# (the e4mc address their game shows). Empty: your own world.
					join=
					""");
				return null;
			}

			Properties props = new Properties();
			try (var in = Files.newBufferedReader(file)) {
				props.load(in);
			}

			String join = props.getProperty("join", "").trim().replaceFirst("^[A-Za-z]+://", "").replaceAll("/+$", "");
			return join.isEmpty() ? null : join;
		} catch (IOException e) {
			Osl.LOG.warn("couldn't read {}", file, e);
			return null;
		}
	}

	/** Instead of our own world: a friend's, if Melty asked. True when connecting. */
	static boolean joinIfAsked(final Minecraft minecraft) {
		String join = joinFailed ? null : joinAddress(minecraft);
		if (join == null || !(minecraft.gui.screen() instanceof TitleScreen title)) {
			return false;
		}

		joining = join;
		Osl.LOG.info("joining a friend's world at {}", join);
		ConnectScreen.startConnecting(title, minecraft, ServerAddress.parseString(join), new ServerData("Mojavecraft", join, ServerData.Type.OTHER), false, null);
		return true;
	}

	/** Our own world just loaded: open it to friends (once per session). */
	private static void open(final Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (published || server == null || server.isPublished() || Boolean.getBoolean("osl.noHost")) {
			return;
		}

		published = true;
		if (Boolean.getBoolean("osl.offlineFriends")) {
			server.setUsesAuthentication(false); // development only: the second dev client has no Microsoft account
		}

		int port = Integer.getInteger("osl.lanPort", HttpUtil.getAvailablePort());
		// friends play survival without commands; the host keeps theirs (the world allows them)
		if (server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, port)) {
			Osl.LOG.info("OSL world open to friends on port {}", port);
		} else {
			Osl.LOG.warn("couldn't open the world to friends on port {}", port);
		}
	}

	/** Every chat line: e4mc's "Local game hosted on domain [...]" gives the host's internet address. */
	public static void onChat(final Component message) {
		if (Minecraft.getInstance().getSingleplayerServer() == null || link != null) {
			return; // a friend's world: its chat names the host's address too
		}

		String found = find(message);
		if (found != null) {
			link = found;
			// fixed words: Melty finds the host's address after them in latest.log
			Osl.LOG.info("Mojavecraft: this world is open to friends at {}", link);
		}
	}

	private static String find(final Component message) {
		Matcher m = E4MC_LINK.matcher(message.getString());
		if (m.find()) {
			return m.group().toLowerCase(java.util.Locale.ROOT);
		}

		for (Component part : message.toFlatList()) {
			if (part.getStyle().getClickEvent() instanceof ClickEvent.CopyToClipboard copy) {
				m = E4MC_LINK.matcher(copy.value());
				if (m.find()) {
					return m.group().toLowerCase(java.util.Locale.ROOT);
				}
			}
		}

		return null;
	}

	/** The disconnect screen's reason, for the log. */
	private static String why(final net.minecraft.client.gui.screens.Screen screen) {
		StringBuilder out = new StringBuilder(screen.getTitle().getString());
		for (java.lang.reflect.Field f : screen.getClass().getDeclaredFields()) {
			try {
				f.setAccessible(true);
				Object v = f.get(screen);
				if (v instanceof Component c) {
					out.append(": ").append(c.getString());
				} else if (v instanceof net.minecraft.network.DisconnectionDetails d) {
					out.append(": ").append(d.reason().getString());
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// best effort
			}
		}

		return out.toString();
	}

	/**
	 * Client tick. A friend's world we couldn't reach, or that closed: back to the title screen, and true (open our
	 * own world). Also shows the note for the player once they're in a world.
	 */
	static boolean tick(final Minecraft minecraft) {
		if (note != null && minecraft.player != null) {
			minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal("§6[Mojave]§r " + note));
			note = null;
		}

		if (joining != null && minecraft.level == null && minecraft.gui.screen() instanceof DisconnectedScreen) {
			Osl.LOG.info("couldn't stay in the friend's world at {} ({}): opening our own", joining, why(minecraft.gui.screen()));
			note = "Couldn't stay in your friend's world (" + joining + "). You're in your own Mojave.";
			joining = null;
			joinFailed = true;
			minecraft.gui.setScreen(new TitleScreen());
			return true;
		}

		return false;
	}
}
