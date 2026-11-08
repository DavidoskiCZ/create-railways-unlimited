package com.davzelenec.railwaysunlimited.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Purely visual, per-installation preferences. Gameplay settings such as the angle step are stored per player in each
 * save instead (see {@code WorldSettings}).
 */
public final class RUClientConfig {
	public static final ModConfigSpec SPEC;
	public static final ModConfigSpec.BooleanValue SHOW_OVERLAY;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
		SHOW_OVERLAY = builder
			.comment("Show the placement angle next to the crosshair while wearing Engineer's Goggles and holding track.")
			.translation("railways_unlimited.configuration.showOverlay")
			.define("showOverlay", true);
		SPEC = builder.build();
	}

	private RUClientConfig() {}
}
