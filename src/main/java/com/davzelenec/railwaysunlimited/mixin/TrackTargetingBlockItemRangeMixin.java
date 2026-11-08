package com.davzelenec.railwaysunlimited.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.davzelenec.railwaysunlimited.compat.CurveRanges;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;

/** See {@link CurveRanges}. */
@Mixin(TrackTargetingBlockItem.class)
public abstract class TrackTargetingBlockItemRangeMixin {
	@ModifyExpressionValue(method = "useOn", at = @At(value = "INVOKE",
		target = "Lnet/createmod/catnip/config/ConfigBase$ConfigInt;get()Ljava/lang/Object;"))
	private Object railwaysUnlimited$longCurves(Object configured) {
		return CurveRanges.atLeastLongestCurve(configured);
	}
}
