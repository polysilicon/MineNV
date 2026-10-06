package dev.osl.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.osl.Economy;
import dev.osl.Osl;
import dev.osl.Space;

/** New Vegas's latest camera and player pose, in Minecraft coordinates (New Vegas converts; the worldspace strip offset is added here). */
public final class HostState {
	/**
	 * @param hostFrame New Vegas's frame number for this pose (echoed in the exported frame)
	 * @param x camera position
	 * @param yaw Minecraft yaw (0 = facing +Z), pitch (positive = down) and roll, in degrees
	 * @param fov vertical field of view, degrees
	 * @param firstPerson whether New Vegas's camera is first person (third person shows Steve)
	 * @param px the player's feet
	 * @param bodyYaw the player's body yaw (third person)
	 * @param build build mode: Minecraft's hand, hotbar and crosshair are shown and take the mouse
	 */
	public record Pose(
		long hostFrame, double x, double y, double z, float yaw, float pitch, float roll, float fov,
		boolean firstPerson, double px, double py, double pz, float bodyYaw, long receivedNanos, boolean build
	) {
	}

	private static final long TIMEOUT_NANOS = 2_000_000_000L;
	private static volatile Pose latest;
	private static volatile int worldspace = Integer.MIN_VALUE;
	/** The pose this frame renders with, taken once per frame so every hook agrees. Render thread only. */
	private static Pose frame;

	private HostState() {
	}

	/** {"t":"cam","f":frame,"p":[x,y,z],"r":[yaw,pitch,roll],"fov":deg,"fp":bool,"pl":[x,y,z],"h":bodyYaw,"ws":formId,"build":bool} */
	static void update(final JsonObject m) {
		if (m.has("ws")) {
			int ws = m.get("ws").getAsInt();
			if (ws != worldspace) {
				worldspace = ws;
				Economy.worldspace(ws);
				Osl.LOG.info("New Vegas worldspace {} -> strip offset {}", Integer.toHexString(ws), Space.offsetX());
			}
		}

		int dx = Space.offsetX();
		JsonArray p = m.getAsJsonArray("p");
		JsonArray r = m.getAsJsonArray("r");
		JsonArray pl = m.has("pl") ? m.getAsJsonArray("pl") : p;
		float yaw = r.get(0).getAsFloat();
		latest = new Pose(
			m.has("f") ? m.get("f").getAsLong() : 0L,
			p.get(0).getAsDouble() + dx, p.get(1).getAsDouble(), p.get(2).getAsDouble(),
			yaw, r.get(1).getAsFloat(), r.size() > 2 ? r.get(2).getAsFloat() : 0.0F,
			m.has("fov") ? m.get("fov").getAsFloat() : 70.0F,
			!m.has("fp") || m.get("fp").getAsBoolean(),
			pl.get(0).getAsDouble() + dx, pl.get(1).getAsDouble(), pl.get(2).getAsDouble(),
			m.has("h") ? m.get("h").getAsFloat() : yaw,
			System.nanoTime(),
			m.has("build") && m.get("build").getAsBoolean()
		);
	}

	/** The latest pose if New Vegas is still sending, else null. */
	static Pose live() {
		Pose p = latest;
		return p != null && System.nanoTime() - p.receivedNanos() < TIMEOUT_NANOS ? p : null;
	}

	/** The pose for the frame being rendered, or null when New Vegas isn't attached. */
	public static Pose frame() {
		return frame;
	}

	public static void beginFrame() {
		frame = live();
		Osl.active = frame != null;
	}
}
