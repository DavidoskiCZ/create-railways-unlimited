package com.davzelenec.railwaysunlimited.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.davzelenec.railwaysunlimited.client.AnchorClicks;
import com.simibubi.create.content.trains.track.TrackBlockOutline;

/**
 * With track in hand Create outlines curves in red, because a connection cannot start on them. Where a click is
 * passed on to an anchor, the outline is shown as valid (white) like on straight track.
 */
@Mixin(TrackBlockOutline.class)
public abstract class TrackBlockOutlineMixin {
	@ModifyArg(method = "drawCurveSelection", index = 3, at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/trains/track/TrackBlockOutline;renderShape(Lnet/minecraft/world/phys/shapes/VoxelShape;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Ljava/lang/Boolean;)V"))
	private static Boolean railwaysUnlimited$validOnAnchorEnd(Boolean valid) {
		if (valid != null && !valid && (AnchorClicks.findTarget() != null || AnchorClicks.findLineTarget() != null))
			return true;
		return valid;
	}
}
