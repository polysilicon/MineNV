package dev.osl;

/**
 * New Vegas worldspaces (the Mojave, the Strip, Freeside...) each get their own strip of the Minecraft world,
 * WORLDSPACE_SPACING blocks apart on X, so builds in one never show up in another. New Vegas sends positions
 * without the offset plus its worldspace's form ID; the world assigns strips in the order first visited (the same for
 * every player) and tells each player's Minecraft its own ("space"). This is that player's side (client).
 */
public final class Space {
	private static volatile int offsetX;

	private Space() {
	}

	public static int offsetX() {
		return offsetX;
	}

	public static void set(final int dx) {
		offsetX = dx;
	}
}
