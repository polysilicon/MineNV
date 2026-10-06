package dev.osl;

/**
 * New Vegas worldspaces (the Mojave, the Strip, Freeside...) each get their own strip of the Minecraft world,
 * WORLDSPACE_SPACING blocks apart on X, so builds in one never show up in another. New Vegas sends positions
 * without the offset plus its worldspace's form ID; Economy assigns slots in the order first visited and saves them.
 */
public final class Space {
	private static volatile int offsetX;

	private Space() {
	}

	public static int offsetX() {
		return offsetX;
	}

	static void setSlot(final int slot) {
		offsetX = slot * Sheets.WORLDSPACE_SPACING;
	}

	/** True for an x inside the strip of the worldspace New Vegas is in now. */
	public static boolean inCurrent(final int x) {
		return Math.abs(x - offsetX) < Sheets.WORLDSPACE_SPACING / 2;
	}
}
