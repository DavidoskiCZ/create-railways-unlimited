package com.davzelenec.railwaysunlimited.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.davzelenec.railwaysunlimited.placement.TrackGeometry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;

/**
 * Trains report every curved edge they enter to a turn listener, which is how navigation decides to slow down for
 * curves. Straight runs made of "straight curves" are not turns, so they are not reported.
 */
@Mixin(TravellingPoint.class)
public abstract class TravellingPointMixin {
	@WrapOperation(method = {"travel", "edgeTraversedFrom"},
		at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/graph/TrackEdge;isTurn()Z"))
	private boolean railwaysUnlimited$straightIsNotATurn(TrackEdge edge, Operation<Boolean> original) {
		return original.call(edge) && !TrackGeometry.isStraight(edge.getTurn());
	}
}
