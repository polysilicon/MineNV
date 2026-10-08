package dev.osl;

import java.util.function.Consumer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Overworld Supply Line: Minecraft in the Mojave.
 * The camera/ground/frame passthrough is based on universal-modder's minecraft-gta5-passthrough example (MIT).
 */
public class Osl implements ModInitializer {
	public static final String ID = "osl";
	public static final Logger LOG = LoggerFactory.getLogger(ID);
	/** True while New Vegas is driving the camera. The integrated server shares this JVM, so both sides read it. */
	public static volatile boolean active;
	/** Minecraft mode: Minecraft's physics move the player and New Vegas follows (0.2.0). Both sides read it. */
	public static volatile boolean takeover;
	/** Where events for New Vegas go (JSON lines); the client's HostLink sets it. */
	public static volatile Consumer<String> events = message -> {};

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			WorldBridge.attach(server);
			Economy.attach(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			Economy.detach();
			WorldBridge.detach();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			WorldBridge.tick(server);
			Economy.tick(server);
		});
		LOG.info("Overworld Supply Line loaded");
	}
}
