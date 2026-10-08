package dev.osl.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.osl.Osl;
import dev.osl.Space;
import dev.osl.WorldBridge;
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft mode: Minecraft plays, New Vegas follows. Minecraft's physics move Steve on New Vegas's ground (the
 * barriers), the player's input arrives through InputBridge, and every frame Steve's feet and look go back to New
 * Vegas ("me"), which moves the Courier and its camera there.
 */
public final class Takeover {
	private Takeover() {
	}

	public static boolean on() {
		return Osl.takeover;
	}

	/** {"t":"takeover","on":bool,"feet":[x,y,z],"yaw":deg} (client thread). */
	static void set(final Minecraft minecraft, final JsonObject m) {
		boolean on = m.get("on").getAsBoolean();
		if (on == Osl.takeover) {
			return;
		}

		Osl.takeover = on;
		LocalPlayer player = minecraft.player;
		if (!on) {
			InputBridge.releaseAll(minecraft);
			Osl.LOG.info("Minecraft mode off: New Vegas controls the Courier");
			return;
		}

		Osl.LOG.info("Minecraft mode on");
		if (minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
			minecraft.options.setCameraType(CameraType.FIRST_PERSON);
		}

		if (player != null && m.has("feet")) {
			JsonArray f = m.getAsJsonArray("feet");
			double x = f.get(0).getAsDouble() + Space.offsetX(), y = f.get(1).getAsDouble(), z = f.get(2).getAsDouble();
			float yaw = m.has("yaw") ? m.get("yaw").getAsFloat() : player.getYRot();
			// on the server too, so its idea of the player matches; Minecraft physics take it from there
			player.getAbilities().flying = false;
			player.getAbilities().mayfly = false;
			player.onUpdateAbilities();
			player.setPos(x, y + 0.05, z);
			player.setYRot(yaw);
			player.setDeltaMovement(Vec3.ZERO);
			WorldBridge.command(String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f %.2f 0", player.getStringUUID(), x, y + 0.05, z, yaw));
			var server = minecraft.getSingleplayerServer();
			var uuid = player.getUUID();
			if (server != null) {
				server.execute(() -> {
					var sp = server.getPlayerList().getPlayer(uuid);
					if (sp != null) {
						sp.getAbilities().flying = false;
						sp.getAbilities().mayfly = false;
						sp.onUpdateAbilities();
					}
				});
			}
		}

		if (minecraft.gui.screen() == null) {
			minecraft.mouseHandler.grabMouse();
		}
	}

	/** Every rendered frame while on: where Steve is drawn, in the worldspace's own coordinates. */
	public static void publish(final float partialTick) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (!Osl.takeover || player == null) {
			return;
		}

		Vec3 at = player.getPosition(partialTick);
		Osl.events.accept(String.format(Locale.ROOT, "{\"t\":\"me\",\"f\":[%.4f,%.4f,%.4f],\"e\":%.3f,\"r\":[%.3f,%.3f],\"ground\":%b}",
			at.x - Space.offsetX(), at.y, at.z, player.getEyeHeight(), player.getViewYRot(partialTick), player.getViewXRot(partialTick),
			player.onGround()));
	}
}
