package com.davzelenec.railwaysunlimited.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.davzelenec.railwaysunlimited.client.AnchorClicks;
import com.simibubi.create.content.trains.track.CurvedTrackInteraction;

import net.neoforged.neoforge.client.event.InputEvent;

/** Lets a click with track on the end of an angled track select the anchor underneath. */
@Mixin(CurvedTrackInteraction.class)
public abstract class CurvedTrackInteractionMixin {
	@Inject(method = "onClickInput", at = @At("HEAD"), cancellable = true)
	private static void railwaysUnlimited$clickAnchor(InputEvent.InteractionKeyMappingTriggered event,
													  CallbackInfoReturnable<Boolean> cir) {
		if (AnchorClicks.redirect(event))
			cir.setReturnValue(true);
	}
}
