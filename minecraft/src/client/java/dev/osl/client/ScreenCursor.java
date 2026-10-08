package dev.osl.client;

import dev.osl.Osl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Minecraft screens (inventory, chests, crafting) inside New Vegas: New Vegas keeps the real mouse, sends its
 * movement while a screen is open, and this moves Minecraft's own cursor and clicks with it. The cursor is drawn
 * into the overlay (the OS cursor never reaches the exported frame).
 */
public final class ScreenCursor {
	private static double x = -1, y = -1;
	private static final boolean[] down = new boolean[2];
	private static boolean wasOpen;

	private ScreenCursor() {
	}

	/** {"t":"cursor","dx":px,"dy":px,"b":[left,right]} (client thread). */
	static void move(final Minecraft minecraft, final double dx, final double dy, final boolean left, final boolean right) {
		Screen screen = minecraft.gui.screen();
		if (screen == null) {
			return;
		}

		long window = minecraft.getWindow().handle();
		int w = minecraft.getWindow().getWidth(), h = minecraft.getWindow().getHeight();
		if (x < 0) {
			x = w / 2.0;
			y = h / 2.0;
		}

		x = Math.clamp(x + dx, 0, w - 1);
		y = Math.clamp(y + dy, 0, h - 1);
		minecraft.mouseHandler.onMove(window, x, y, dx, dy);
		button(minecraft, window, 0, left);
		button(minecraft, window, 1, right);
	}

	private static void button(final Minecraft minecraft, final long window, final int b, final boolean pressed) {
		if (down[b] != pressed) {
			down[b] = pressed;
			minecraft.mouseHandler.onButton(window, new MouseButtonInfo(InputBridge.sdlButton(b), 0), pressed ? 1 : 0);
		}
	}

	/** Every client tick: tell New Vegas when a screen opens or closes, so it freezes or frees its controls. */
	static void tick(final Minecraft minecraft) {
		boolean open = minecraft.gui.screen() != null && minecraft.level != null;
		if (open != wasOpen) {
			wasOpen = open;
			x = y = -1;
			down[0] = down[1] = false;
			if (open && Takeover.on()) {
				InputBridge.centreCursor(minecraft);
			}
			Osl.events.accept("{\"t\":\"screen\",\"open\":" + open + "}");
		}
	}

	public static boolean open() {
		return wasOpen;
	}

	/** Cursor position in GUI-scaled coordinates, or null when none is shown. */
	public static int[] guiPosition(final Minecraft minecraft) {
		if (!wasOpen || x < 0) {
			return null;
		}

		double scale = minecraft.getWindow().getGuiScale();
		return new int[] {(int) (x / scale), (int) (y / scale)};
	}
}
