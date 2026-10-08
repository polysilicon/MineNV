package dev.osl.client;

import dev.osl.Osl;
import dev.osl.WorldBridge;
import java.util.List;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

public class OslClient implements ClientModInitializer {
	/** The Mojave's Minecraft world: an empty (void) survival world, so only what the player builds is Minecraft. */
	private static final String WORLD = "mojave";
	/** Run on the server once the player has joined. */
	private static final List<String> SETUP = List.of(
		"gamerule advance_time false",
		"gamerule advance_weather false",
		"gamerule spawn_mobs false",
		"gamerule spawn_monsters false",
		"gamerule spawn_patrols false",
		"gamerule spawn_phantoms false",
		"gamerule spawn_wandering_traders false",
		"gamerule send_command_feedback false",
		"gamerule log_admin_commands false",
		"gamerule keep_inventory true",
		"gamerule fall_damage false",
		"gamerule drowning_damage false",
		"gamerule show_advancement_messages false",
		"gamerule player_movement_check false",
		"difficulty peaceful",
		"time set noon",
		"weather clear"
	);
	/** Started by the launcher behind New Vegas: no window, and quit once New Vegas has gone. */
	public static final boolean START_HIDDEN = Boolean.getBoolean("osl.startHidden");
	private static final long QUIT_AFTER_NANOS = 30_000_000_000L;
	private static boolean hidden;
	private static boolean configured;
	private static boolean worldRequested;
	/** Server ticks until the setup commands run (the player isn't in the player list yet when JOIN fires). */
	private static int setupIn = -1;
	private static int respawnIn;

	@Override
	public void onInitializeClient() {
		HostLink.launch();
		ClientTickEvents.END_CLIENT_TICK.register(OslClient::tick);
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
			int[] at = ScreenCursor.guiPosition(client);
			if (at != null) {
				// a small arrow: the OS cursor never reaches the picture New Vegas shows
				for (int i = 0; i < 7; i++) {
					g.fill(at[0], at[1] + i, at[0] + i + 1, at[1] + i + 1, 0xFF000000);
					g.fill(at[0], at[1] + i, at[0] + Math.max(i, 1), at[1] + i + 1, 0xFFFFFFFF);
				}
			}
		}));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			// Steve hovers where the Courier stands: never falls into the void before New Vegas sends ground
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			setupIn = 10;
			// fixed words: Melty's first-run setup and the launcher wait for this line in latest.log
			Osl.LOG.info("OSL ready: world {} open", WORLD);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (setupIn > 0 && --setupIn == 0) {
				SETUP.forEach(WorldBridge::command);
			}
		});
	}

	/** True while New Vegas is connected over the link. */
	public static boolean linked() {
		return HostLink.connections() > 0;
	}

	private static void tick(final Minecraft minecraft) {
		if (START_HIDDEN && !hidden) {
			hidden = true;
			org.lwjgl.sdl.SDLVideo.SDL_HideWindow(minecraft.getWindow().handle());
			Osl.LOG.info("window hidden: New Vegas shows Minecraft's picture");
		}

		if (START_HIDDEN && HostLink.everConnected() && !linked() && System.nanoTime() - HostLink.lastSeenNanos() > QUIT_AFTER_NANOS) {
			Osl.LOG.info("New Vegas has gone: quitting");
			minecraft.stop();
			return;
		}

		// a death (the void) would leave the death screen over the host's picture: respawn straight away
		ScreenCursor.tick(minecraft);
		if (minecraft.player != null && minecraft.gui.screen() instanceof DeathScreen && --respawnIn <= 0) {
			respawnIn = 40;
			minecraft.player.respawn();
		}

		if (!configured) {
			configured = true;
			configure(minecraft.options);
		}

		if (!worldRequested && minecraft.level == null && minecraft.gui.screen() instanceof TitleScreen && !Boolean.getBoolean("osl.noAutoWorld")) {
			worldRequested = true;
			openWorld(minecraft);
		}
	}

	/** Settings for sitting behind New Vegas: keep running unfocused, and no sky/cloud/bobbing effects in the picture. */
	private static void configure(final Options options) {
		options.pauseOnLostFocus = false;
		options.onboardAccessibility = false;
		options.tutorialStep = TutorialSteps.NONE;
		options.cloudStatus().set(CloudStatus.OFF);
		options.bobView().set(false);
		options.vignette().set(false);
		options.improvedTransparency().set(false);
		options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		options.fovEffectScale().set(0.0);
		options.damageTiltStrength().set(0.0);
		options.menuBackgroundBlurriness().set(0);
		options.enableVsync().set(false);
		// New Vegas runs at 60 fps: rendering faster only competes with it for the GPU
		options.framerateLimit().set(60);
		options.save();
	}

	private static void openWorld(final Minecraft minecraft) {
		if (minecraft.getLevelSource().levelExists(WORLD)) {
			Osl.LOG.info("opening world {}", WORLD);
			minecraft.createWorldOpenFlows().openWorld(WORLD, () -> minecraft.gui.setScreen(new TitleScreen()));
		} else {
			Osl.LOG.info("creating world {}", WORLD);
			LevelSettings settings = new LevelSettings("Mojave", GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
			minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false), OslClient::voidWorld, minecraft.gui.screen());
		}
	}

	/** A flat world with a single layer of air: nothing but what gets built (New Vegas's ground arrives as barriers). */
	private static WorldDimensions voidWorld(final HolderLookup.Provider registries) {
		FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(
			Optional.of(HolderSet.direct()), registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS), List.of()
		);
		flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.AIR));
		flat.updateLayers();
		return WorldPresets.createNormalWorldDimensions(registries).replaceOverworldGenerator(registries, new FlatLevelSource(flat));
	}
}
