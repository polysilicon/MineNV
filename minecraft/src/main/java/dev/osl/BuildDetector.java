package dev.osl;

import java.util.Map;

/**
 * Recognises the builds in builds.json around a point. Pure logic over {@link BlockView}, so it is tested without
 * Minecraft (BuildDetectorTest); Economy runs it on the server every PERK_SYNC_SECONDS.
 */
public final class BuildDetector {
	/** What the detector needs to know about the world: the block id at a position ("minecraft:farmland"). */
	public interface BlockView {
		String block(int x, int y, int z);
	}

	private static final java.util.Set<String> CROPS = java.util.Set.of(
		"minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots", "minecraft:melon_stem",
		"minecraft:pumpkin_stem", "minecraft:torchflower_crop", "minecraft:pitcher_crop", "minecraft:attached_melon_stem",
		"minecraft:attached_pumpkin_stem"
	);
	private static final java.util.Set<String> BEACON_BASE = java.util.Set.of(
		"minecraft:iron_block", "minecraft:gold_block", "minecraft:diamond_block", "minecraft:emerald_block", "minecraft:netherite_block"
	);
	/** Vertical reach of a scan, above and below the centre. */
	private static final int HEIGHT = 8;

	private BuildDetector() {
	}

	/** True when the build is found within its radius of (cx, cy, cz). */
	public static boolean found(final Sheets.Build build, final BlockView view, final int cx, final int cy, final int cz) {
		Map<String, Integer> p = build.params();
		return switch (build.rule()) {
			case "farmland_with_crops" -> farm(view, cx, cy, cz, p.get("radius"), p.get("min"));
			case "bed_door_roof" -> house(view, cx, cy, cz, p.get("radius"), p.get("door_within"), p.get("roof_within"));
			case "active_beacon" -> beacon(view, cx, cy, cz, p.get("radius"), p.get("min_level"));
			default -> throw new IllegalArgumentException("no detector for rule " + build.rule());
		};
	}

	static boolean farm(final BlockView v, final int cx, final int cy, final int cz, final int r, final int min) {
		int n = 0;
		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				for (int y = cy - HEIGHT; y <= cy + HEIGHT; y++) {
					if ("minecraft:farmland".equals(v.block(x, y, z)) && CROPS.contains(v.block(x, y + 1, z)) && ++n >= min) {
						return true;
					}
				}
			}
		}

		return false;
	}

	static boolean house(final BlockView v, final int cx, final int cy, final int cz, final int r, final int doorWithin, final int roofWithin) {
		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				for (int y = cy - HEIGHT; y <= cy + HEIGHT; y++) {
					String b = v.block(x, y, z);
					if (b != null && b.endsWith("_bed") && roofed(v, x, y, z, roofWithin) && doorNear(v, x, y, z, doorWithin)) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/** Something solid (a player block, or New Vegas's own roof as a barrier) above the bed. */
	private static boolean roofed(final BlockView v, final int x, final int y, final int z, final int within) {
		for (int dy = 1; dy <= within; dy++) {
			String b = v.block(x, y + dy, z);
			if (b != null && !b.equals("minecraft:air") && !b.endsWith("_bed")) {
				return true;
			}
		}

		return false;
	}

	private static boolean doorNear(final BlockView v, final int x, final int y, final int z, final int within) {
		for (int dx = -within; dx <= within; dx++) {
			for (int dz = -within; dz <= within; dz++) {
				for (int dy = -2; dy <= 2; dy++) {
					String b = v.block(x + dx, y + dy, z + dz);
					if (b != null && b.endsWith("_door") && !b.endsWith("trapdoor")) {
						return true;
					}
				}
			}
		}

		return false;
	}

	static boolean beacon(final BlockView v, final int cx, final int cy, final int cz, final int r, final int minLevel) {
		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				for (int y = cy - HEIGHT; y <= cy + HEIGHT; y++) {
					if ("minecraft:beacon".equals(v.block(x, y, z)) && beaconLevel(v, x, y, z) >= minLevel) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/** Minecraft's own rule: level n needs a full (2n+1)x(2n+1) layer of base blocks n blocks below, up to 4. */
	static int beaconLevel(final BlockView v, final int x, final int y, final int z) {
		int level = 0;
		for (int n = 1; n <= 4; n++) {
			for (int dx = -n; dx <= n; dx++) {
				for (int dz = -n; dz <= n; dz++) {
					if (!BEACON_BASE.contains(v.block(x + dx, y - n, z + dz))) {
						return level;
					}
				}
			}

			level = n;
		}

		return level;
	}
}
