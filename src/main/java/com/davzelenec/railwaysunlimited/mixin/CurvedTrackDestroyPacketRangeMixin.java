package com.davzelenec.railwaysunlimited.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.davzelenec.railwaysunlimited.compat.CurveRanges;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.trains.track.CurvedTrackDestroyPacket;

/** See {@link CurveRanges}. */
@Mixin(CurvedTrackDestroyPacket.class)
public abstract class CurvedTrackDestroyPacketRangeMixin {
	@ModifyExpressionValue(method = {"applySettings", "maxRange"}, at = @At(value = "INVOKE",
		target = "Lnet/createmod/catnip/config/ConfigBase$ConfigInt;get()Ljava/lang/Object;"))
	private Object railwaysUnlimited$longCurves(Object configured) {
		return CurveRanges.atLeastLongestCurve(configured);
	}
}
