package com.davzelenec.railwaysunlimited.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.davzelenec.railwaysunlimited.snap.SnapSettings;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.trains.track.TrackPlacement;

import net.minecraft.world.entity.player.Player;

/** Create's own placement uses the player's per-save maximum length instead of the server config. */
@Mixin(TrackPlacement.class)
public abstract class TrackPlacementMixin {
	@ModifyExpressionValue(method = "tryConnect", at = @At(value = "INVOKE",
		target = "Lnet/createmod/catnip/config/ConfigBase$ConfigInt;get()Ljava/lang/Object;"))
	private static Object railwaysUnlimited$playerMaxLength(Object configured, @Local(argsOnly = true) Player player) {
		return SnapSettings.getMaxLength(player);
	}
}
