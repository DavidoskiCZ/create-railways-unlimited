package com.davzelenec.railwaysunlimited.compat;

import com.davzelenec.railwaysunlimited.snap.PlayerSettings;

/**
 * Create derives some reach checks for curves (breaking them, putting stations and signals on them) from the maximum
 * placement length. Curves may now be as long as {@link PlayerSettings#MAX_MAX_LENGTH}, so those checks must allow it.
 */
public final class CurveRanges {
	private CurveRanges() {}

	public static Object atLeastLongestCurve(Object configured) {
		return Math.max((Integer) configured, PlayerSettings.MAX_MAX_LENGTH);
	}
}
