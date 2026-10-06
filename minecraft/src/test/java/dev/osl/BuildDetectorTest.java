package dev.osl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** builds.json rows against hand-built worlds: each detector finds its build and nothing short of it. */
class BuildDetectorTest {
	/** A sparse world: everything not set is air. */
	static final class World implements BuildDetector.BlockView {
		final Map<Long, String> blocks = new HashMap<>();

		World set(final int x, final int y, final int z, final String id) {
			this.blocks.put(key(x, y, z), id);
			return this;
		}

		@Override
		public String block(final int x, final int y, final int z) {
			return this.blocks.getOrDefault(key(x, y, z), "minecraft:air");
		}

		private static long key(final int x, final int y, final int z) {
			return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
		}
	}

	static Sheets.Build build(final String id) {
		return Sheets.BUILDS.stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
	}

	@Test
	void farmNeedsEnoughPlantedFarmland() {
		World w = new World();
		for (int i = 0; i < 7; i++) {
			w.set(i, 63, 0, "minecraft:farmland").set(i, 64, 0, "minecraft:wheat");
		}
		w.set(7, 63, 0, "minecraft:farmland"); // no crop
		assertFalse(BuildDetector.found(build("farm"), w, 0, 64, 0));
		w.set(7, 64, 0, "minecraft:carrots");
		assertTrue(BuildDetector.found(build("farm"), w, 0, 64, 0));
	}

	@Test
	void farmOutOfRadiusIsNotFound() {
		World w = new World();
		for (int i = 0; i < 8; i++) {
			w.set(100 + i, 63, 0, "minecraft:farmland").set(100 + i, 64, 0, "minecraft:wheat");
		}
		assertFalse(BuildDetector.found(build("farm"), w, 0, 64, 0));
	}

	@Test
	void houseNeedsBedDoorAndRoof() {
		World w = new World().set(0, 64, 0, "minecraft:red_bed").set(3, 64, 0, "minecraft:oak_door");
		assertFalse(BuildDetector.found(build("house"), w, 0, 64, 0), "no roof");
		w.set(0, 67, 0, "minecraft:oak_planks");
		assertTrue(BuildDetector.found(build("house"), w, 0, 64, 0));
	}

	@Test
	void houseRoofCanBeNewVegasGround() {
		// a bed under a New Vegas overhang: the overhang is barriers in Minecraft
		World w = new World().set(0, 64, 0, "minecraft:white_bed").set(2, 64, 1, "minecraft:spruce_door").set(0, 66, 0, "minecraft:barrier");
		assertTrue(BuildDetector.found(build("house"), w, 0, 64, 0));
	}

	@Test
	void trapdoorIsNotADoor() {
		World w = new World().set(0, 64, 0, "minecraft:red_bed").set(3, 64, 0, "minecraft:oak_trapdoor").set(0, 66, 0, "minecraft:stone");
		assertFalse(BuildDetector.found(build("house"), w, 0, 64, 0));
	}

	@Test
	void beaconNeedsAFullPyramidLayer() {
		World w = new World().set(0, 64, 0, "minecraft:beacon");
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 1 || dz != 1) {
					w.set(dx, 63, dz, "minecraft:iron_block");
				}
			}
		}
		assertEquals(0, BuildDetector.beaconLevel(w, 0, 64, 0));
		assertFalse(BuildDetector.found(build("beacon"), w, 0, 64, 0));
		w.set(1, 63, 1, "minecraft:gold_block");
		assertEquals(1, BuildDetector.beaconLevel(w, 0, 64, 0));
		assertTrue(BuildDetector.found(build("beacon"), w, 0, 64, 0));
	}

	@Test
	void everyBuildHasADetectorAndAPerk() {
		World empty = new World();
		for (Sheets.Build b : Sheets.BUILDS) {
			assertFalse(BuildDetector.found(b, empty, 0, 64, 0), b.id());
			assertTrue(Sheets.PERKS.containsKey(b.perk()), b.id());
		}
	}
}
