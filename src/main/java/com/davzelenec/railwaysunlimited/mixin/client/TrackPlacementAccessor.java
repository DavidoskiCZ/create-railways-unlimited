package com.davzelenec.railwaysunlimited.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.trains.track.TrackPlacement;

@Mixin(TrackPlacement.class)
public interface TrackPlacementAccessor {
	@Accessor("extraTipWarmup")
	static void railwaysUnlimited$setExtraTipWarmup(int value) {
		throw new AssertionError();
	}
}
