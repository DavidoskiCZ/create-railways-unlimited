package com.davzelenec.railwaysunlimited.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.davzelenec.railwaysunlimited.placement.TrackGeometry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.graph.TrackEdge;

/** Manually driven trains also cap their speed on curves; straight curves should not count. */
@Mixin(CarriageContraptionEntity.class)
public abstract class CarriageContraptionEntityMixin {
	@WrapOperation(method = "control",
		at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/graph/TrackEdge;isTurn()Z"))
	private boolean railwaysUnlimited$straightIsNotATurn(TrackEdge edge, Operation<Boolean> original) {
		return original.call(edge) && !TrackGeometry.isStraight(edge.getTurn());
	}
}
