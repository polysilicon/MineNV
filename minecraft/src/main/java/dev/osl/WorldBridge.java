package dev.osl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Server-side half: New Vegas's ground as invisible barrier blocks, commands, and block changes back to New Vegas. */
public final class WorldBridge {
	private static volatile MinecraftServer server;
	/** Barriers we placed (so a reset only removes ours, never the player's builds). */
	private static final Set<BlockPos> barriers = ConcurrentHashMap.newKeySet();
	/** Block changes this tick (server thread only): true = now solid, false = gone. Sent at the end of the tick. */
	private static final Map<BlockPos, Boolean> changes = new LinkedHashMap<>();
	/** While placing or removing the host's own ground: those changes aren't news to the host (server thread only). */
	private static boolean placingGround;
	/** How far from a player their New Vegas may place ground, and hears about block changes. */
	private static final int NEAR = 96, REPORT = 128;

	private WorldBridge() {
	}

	static void attach(final MinecraftServer s) {
		server = s;
	}

	static void detach() {
		server = null;
		barriers.clear();
	}

	public static boolean ready() {
		return server != null;
	}

	static MinecraftServer server() {
		return server;
	}

	/**
	 * Columns of solid ground from a player's New Vegas: {x, z, yBottom, yTop, ...} in their worldspace's block
	 * coordinates (inclusive), shifted into its strip here. Only air is replaced, and only near that player.
	 */
	static void solid(final MinecraftServer s, final ServerPlayer player, final Courier c, final int[] columns) {
		ServerLevel level = s.overworld();
		BlockState barrier = Blocks.BARRIER.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos near = player.blockPosition();
		placingGround = true;
		for (int i = 0; i + 3 < columns.length; i += 4) {
			int x = columns[i] + c.offsetX, z = columns[i + 1];
			if (Math.abs(x - near.getX()) > NEAR || Math.abs(z - near.getZ()) > NEAR) {
				continue;
			}

			for (int y = Math.max(columns[i + 2], level.getMinY()), top = Math.min(columns[i + 3], columns[i + 2] + 64); y <= top; y++) {
				pos.set(x, y, z);
				if (level.isInWorldBounds(pos) && level.getBlockState(pos).isAir()) {
					level.setBlock(pos, barrier, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
					barriers.add(pos.immutable());
				}
			}
		}

		placingGround = false;
	}

	/** Remove the barriers around this player (their New Vegas teleported, or probes the ground again). */
	static void clearSolid(final MinecraftServer s, final ServerPlayer player) {
		ServerLevel level = s.overworld();
		placingGround = true;
		BlockPos c = player.blockPosition();
		boolean alone = s.getPlayerList().getPlayers().stream().noneMatch(o -> o != player && o.blockPosition().closerThan(c, NEAR * 2.0));
		if (alone) {
			// nobody else's ground is around: everything goes, also strays from earlier sessions
			sweepBarriers(level, c);
		}

		for (java.util.Iterator<BlockPos> it = barriers.iterator(); it.hasNext();) {
			BlockPos pos = it.next();
			if (Math.abs(pos.getX() - c.getX()) > NEAR || Math.abs(pos.getZ() - c.getZ()) > NEAR) {
				continue;
			}

			if (alone && level.getBlockState(pos).is(Blocks.BARRIER)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}

			if (alone) {
				it.remove();
			}
		}

		placingGround = false;
	}

	/**
	 * Every barrier around the player goes (players can't place barriers in survival, so they are all New Vegas's
	 * ground, some from earlier sessions that this session never tracked); New Vegas probes the ground again after.
	 * The Mojave's ground is the same for everyone, so with a friend nearby their barriers stay (they're the same ones).
	 */
	private static void sweepBarriers(final ServerLevel level, final BlockPos c) {
		int r = Sheets.BARRIER_SWEEP_RADIUS;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int removed = 0;
		for (int x = -r; x <= r; x++) {
			for (int z = -r; z <= r; z++) {
				for (int y = -48; y <= 48; y++) {
					p.set(c.getX() + x, c.getY() + y, c.getZ() + z);
					if (level.isLoaded(p) && level.getBlockState(p).is(Blocks.BARRIER)) {
						level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
						removed++;
					}
				}
			}
		}

		if (removed > 0) {
			Osl.LOG.info("ground reset: removed {} barrier blocks around the player", removed);
		}
	}

	/** Run a command as the server (op). Results go to the log, not to chat (send_command_feedback is off). */
	public static void command(final String command) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			Osl.LOG.info("command: {}", command);
			// silent: the player's own commands answer in chat, the mashup's don't
			s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), command);
		});
	}

	private static boolean solidForHost(final ServerLevel level, final BlockPos pos, final BlockState state) {
		return !state.isAir() && !state.is(Blocks.BARRIER) && !state.getCollisionShape(level, pos).isEmpty();
	}

	/** Every server tick: block changes go to New Vegas. */
	static void tick(final MinecraftServer s) {
		flush(s);
	}

	/** Server thread, from Level.setBlock: remember the change; flushed once per tick. */
	public static void onBlockChanged(final ServerLevel level, final BlockPos pos, final BlockState state) {
		if (server == null || placingGround || level != level.getServer().overworld()) {
			return;
		}

		changes.put(pos.immutable(), solidForHost(level, pos, state));
	}

	/** While on, block changes are the host's own ground being edited (not reported as blocks to collide with). */
	static void quietGround(final boolean on) {
		placingGround = on;
	}

	/**
	 * End of each server tick: {"t":"blocks","set":[x,y,z,...],"clear":[x,y,z,...]} to each player's New Vegas, the
	 * changes in their worldspace's strip near them, in its own coordinates.
	 */
	static void flush(final MinecraftServer s) {
		if (changes.isEmpty()) {
			return;
		}

		for (Courier c : Courier.all()) {
			ServerPlayer player = c.player(s);
			if (player == null || !c.active) {
				continue;
			}

			BlockPos near = player.blockPosition();
			StringBuilder set = new StringBuilder();
			StringBuilder clear = new StringBuilder();
			for (Map.Entry<BlockPos, Boolean> e : changes.entrySet()) {
				BlockPos p = e.getKey();
				if (!c.inStrip(p.getX()) || Math.abs(p.getX() - near.getX()) > REPORT || Math.abs(p.getZ() - near.getZ()) > REPORT) {
					continue;
				}

				StringBuilder b = e.getValue() ? set : clear;
				b.append(b.isEmpty() ? "" : ",").append(p.getX() - c.offsetX).append(',').append(p.getY()).append(',').append(p.getZ());
			}

			if (!set.isEmpty() || !clear.isEmpty()) {
				c.send(s, "{\"t\":\"blocks\",\"set\":[" + set + "],\"clear\":[" + clear + "]}");
			}
		}

		changes.clear();
	}

	/** Every solid block within `radius` of the player, as one "blocks" message to their New Vegas. */
	static void sync(final MinecraftServer s, final ServerPlayer player, final Courier c, final int radius) {
		ServerLevel level = s.overworld();
		BlockPos center = player.blockPosition();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		StringBuilder set = new StringBuilder();
		int found = 0;
		for (int x = -radius; x <= radius && found < 3000; x++) {
			for (int z = -radius; z <= radius && found < 3000; z++) {
				for (int y = -24; y <= 40; y++) {
					p.set(center.getX() + x, center.getY() + y, center.getZ() + z);
					if (!level.isInWorldBounds(p) || !level.isLoaded(p)) {
						continue;
					}

					if (solidForHost(level, p, level.getBlockState(p))) {
						set.append(set.isEmpty() ? "" : ",").append(p.getX() - c.offsetX).append(',').append(p.getY()).append(',').append(p.getZ());
						found++;
					}
				}
			}
		}

		c.send(s, "{\"t\":\"blocks\",\"set\":[" + set + "],\"clear\":[]}");
	}
}
