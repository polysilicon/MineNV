package dev.osl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Minecraft mode: New Vegas has the focus and forwards its keys (SDL scancodes, sheets/keymap.json), mouse movement,
 * buttons, wheel and typed text. They are replayed into Minecraft's own input handlers as if its (hidden) window had
 * focus, and a virtual keyboard answers InputConstants.isKeyDown. Same approach as SkyCraft's InputBridge (MIT).
 */
public final class InputBridge {
	private static final boolean[] KEYS = new boolean[512];
	private static final boolean[] BUTTONS = new boolean[8];
	private static double cursorX = 640, cursorY = 360;
	private static int modifiers;

	private InputBridge() {
	}

	public static boolean isKeyDown(final int scancode) {
		return scancode >= 0 && scancode < KEYS.length && KEYS[scancode];
	}

	static void key(final Minecraft minecraft, final int scancode, final boolean down) {
		if (scancode <= 0 || scancode >= KEYS.length) {
			return;
		}

		boolean was = KEYS[scancode];
		KEYS[scancode] = down;
		updateModifiers();
		int action = down ? (was ? 2 : 1) : 0; // 2 = repeat
		int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) modifiers, true);
		minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), action, new KeyEvent(scancode, keycode, modifiers));
	}

	static void move(final Minecraft minecraft, final double dx, final double dy) {
		cursorX += dx;
		cursorY += dy;
		if (minecraft.gui.screen() != null) {
			// a screen has a cursor: keep it on the window
			cursorX = Math.clamp(cursorX, 0, minecraft.getWindow().getWidth() - 1);
			cursorY = Math.clamp(cursorY, 0, minecraft.getWindow().getHeight() - 1);
		}

		minecraft.mouseHandler.onMove(minecraft.getWindow().handle(), cursorX, cursorY, dx, dy);
	}

	static void button(final Minecraft minecraft, final int button, final boolean down) {
		if (button < 0 || button >= BUTTONS.length || BUTTONS[button] == down) {
			return;
		}

		BUTTONS[button] = down;
		minecraft.mouseHandler.onButton(minecraft.getWindow().handle(), new MouseButtonInfo(button, modifiers), down ? 1 : 0);
	}

	static void wheel(final Minecraft minecraft, final double notches) {
		minecraft.mouseHandler.onScroll(minecraft.getWindow().handle(), 0.0, notches);
	}

	static void text(final Minecraft minecraft, final String text) {
		if (minecraft.gui.screen() != null) {
			minecraft.keyboardHandler.textInput(minecraft.getWindow().handle(), text);
		}
	}

	/** A screen opened: the cursor starts in the middle, like Minecraft's own. */
	static void centreCursor(final Minecraft minecraft) {
		cursorX = minecraft.getWindow().getWidth() / 2.0;
		cursorY = minecraft.getWindow().getHeight() / 2.0;
	}

	private static void updateModifiers() {
		int m = 0;
		if (KEYS[225]) m |= 0x0001; // SDL_KMOD_LSHIFT
		if (KEYS[229]) m |= 0x0002; // SDL_KMOD_RSHIFT
		if (KEYS[224]) m |= 0x0040; // SDL_KMOD_LCTRL
		if (KEYS[228]) m |= 0x0080; // SDL_KMOD_RCTRL
		if (KEYS[226]) m |= 0x0100; // SDL_KMOD_LALT
		if (KEYS[230]) m |= 0x0200; // SDL_KMOD_RALT
		modifiers = m;
	}

	/** Lift every key and button still held (Minecraft mode ended, the link dropped). */
	static void releaseAll(final Minecraft minecraft) {
		for (int sc = 0; sc < KEYS.length; sc++) {
			if (KEYS[sc]) {
				key(minecraft, sc, false);
			}
		}

		for (int b = 0; b < BUTTONS.length; b++) {
			if (BUTTONS[b]) {
				button(minecraft, b, false);
			}
		}
	}
}
