package com.davzelenec.railwaysunlimited.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.davzelenec.railwaysunlimited.placement.AngledTrackPlacement;
import com.simibubi.create.content.trains.track.TrackBlockItem;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

@Mixin(TrackBlockItem.class)
public abstract class TrackBlockItemMixin {
	@Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
	private void railwaysUnlimited$placeAngledTrack(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
		InteractionResult result = AngledTrackPlacement.handleUseOn(context);
		if (result != null)
			cir.setReturnValue(result);
	}

	/** Clicking into the air: only the straight-track mode places anything (continuing at the start's height). */
	@Inject(method = "use", at = @At("HEAD"), cancellable = true)
	private void railwaysUnlimited$straightIntoAir(Level level, Player player, InteractionHand hand,
												   CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
		InteractionResult result = AngledTrackPlacement.handleUseInAir(level, player, hand);
		if (result != null)
			cir.setReturnValue(new InteractionResultHolder<>(result, player.getItemInHand(hand)));
	}
}
