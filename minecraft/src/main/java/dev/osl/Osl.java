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
			NpcWar.clear();
			Economy.detach();
			WorldBridge.detach();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			WorldBridge.tick(server);
			Economy.tick(server);
			NpcWar.tick(server);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(NpcWar::onEntityLoad);
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(NpcWar::useEgg);
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((p, l, h, e, hit) -> NpcWar.useEggOnEntity(p, l, h, e));
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(NpcWar::useEggInAir);
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, ctx, sel) -> NpcWar.registerCommands(d));
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> {
			if (e instanceof net.minecraft.server.level.ServerPlayer p) {
				NpcWar.onDeath(p);
			}
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> NpcWar.afterRespawn(newP));
		LOG.info("Overworld Supply Line loaded");
	}
}
