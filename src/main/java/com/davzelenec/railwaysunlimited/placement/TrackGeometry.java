package com.davzelenec.railwaysunlimited.placement;

import com.simibubi.create.content.trains.track.BezierConnection;

import net.minecraft.world.phys.Vec3;

public final class TrackGeometry {
	private TrackGeometry() {}

	public static Vec3 horizontal(Vec3 v) {
		return new Vec3(v.x, 0, v.z);
	}

	/** Horizontal unit vector, or {@link Vec3#ZERO} when the vector is vertical. */
	public static Vec3 horizontalDir(Vec3 v) {
		Vec3 h = horizontal(v);
		double len = h.length();
		return len < 1e-9 ? Vec3.ZERO : h.scale(1 / len);
	}

	/** Rise over run of a direction vector. */
	public static double grade(Vec3 v) {
		double len = Math.sqrt(v.x * v.x + v.z * v.z);
		return len < 1e-9 ? 0 : v.y / len;
	}

	/** Unit 3D direction for a horizontal heading and a grade. */
	public static Vec3 withGrade(Vec3 horizontalDir, double grade) {
		return new Vec3(horizontalDir.x, grade, horizontalDir.z).normalize();
	}

	/** Heading from an angle measured from +X towards +Z, in degrees. */
	public static Vec3 fromYaw(double degrees) {
		double rad = Math.toRadians(degrees);
		return new Vec3(Math.cos(rad), 0, Math.sin(rad));
	}

	public static double yawOf(Vec3 dir) {
		return Math.toDegrees(Math.atan2(dir.z, dir.x));
	}

	/**
	 * Compass-style track angle shown to players: 0 runs north-south, 90 east-west, measured clockwise.
	 * Tracks are bidirectional, so the result is in [0, 180).
	 */
	public static int displayAngle(Vec3 dir) {
		double bearing = Math.toDegrees(Math.atan2(dir.x, -dir.z));
		long rounded = Math.round(bearing);
		return (int) (((rounded % 180) + 180) % 180);
	}

	public static double snapYaw(double yawDegrees, int step) {
		return Math.round(yawDegrees / step) * (double) step;
	}

	/**
	 * A curve is horizontally straight when both ends point at each other along the line between them.
	 * Such curves are only used for straight track at angles Create cannot express with blocks (and for
	 * height transitions on straight track), so trains should not slow down on them.
	 */
	public static boolean isStraight(BezierConnection bc) {
		if (bc == null)
			return false;
		Vec3 a1 = horizontalDir(bc.axes.getFirst());
		Vec3 a2 = horizontalDir(bc.axes.getSecond());
		if (a1 == Vec3.ZERO || a2 == Vec3.ZERO)
			return false;
		if (a1.dot(a2) > -0.9999)
			return false;
		Vec3 along = horizontalDir(bc.starts.getSecond().subtract(bc.starts.getFirst()));
		if (along == Vec3.ZERO)
			return false;
		return along.dot(a1) > 0.9999;
	}
}
