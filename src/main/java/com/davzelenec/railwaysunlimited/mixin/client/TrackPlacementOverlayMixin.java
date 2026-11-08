package com.davzelenec.railwaysunlimited.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.trains.track.TrackPlacementOverlay;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Create's "Hold Left Control for maximized turn" tip is replaced by this mod's single key line
 * ({@code TurnHintOverlay}), which lists that key together with this mod's own.
 */
@Mixin(TrackPlacementOverlay.class)
public abstract class TrackPlacementOverlayMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void railwaysUnlimited$replacedByKeyLine(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		ci.cancel();
	}
}
