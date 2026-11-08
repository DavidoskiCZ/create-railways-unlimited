package com.davzelenec.railwaysunlimited.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.davzelenec.railwaysunlimited.client.PlacementPreview;
import com.simibubi.create.content.trains.track.TrackPlacement;

/** Replaces Create's placement preview whenever this mod would do the placing. */
@Mixin(TrackPlacement.class)
public abstract class TrackPlacementMixin {
	@Inject(method = "clientTick", at = @At("HEAD"), cancellable = true)
	private static void railwaysUnlimited$preview(CallbackInfo ci) {
		if (PlacementPreview.tick())
			ci.cancel();
	}
}
