package com.davzelenec.railwaysunlimited.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackPlacement;

/** Create's previewed connection, so the goggle overlay can show the grade Create is about to build. */
@Mixin(TrackPlacement.PlacementInfo.class)
public interface PlacementInfoAccessor {
	@Accessor("curve")
	BezierConnection railwaysUnlimited$getCurve();

	@Accessor("valid")
	boolean railwaysUnlimited$isValid();
}
