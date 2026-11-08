package com.davzelenec.railwaysunlimited.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.davzelenec.railwaysunlimited.compat.TrackGraphCleanup;
import com.davzelenec.railwaysunlimited.block.AngledTrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * An anchor losing its last curve takes its graph node with it (see {@link TrackGraphCleanup}); one left with a single
 * curve lays it flat again.
 */
@Mixin(TrackBlockEntity.class)
public abstract class TrackBlockEntityMixin {
	@Inject(method = "removeConnection", at = @At("HEAD"))
	private void railwaysUnlimited$removeAnchorNode(BlockPos target, CallbackInfo ci) {
		TrackGraphCleanup.beforeConnectionRemoved((TrackBlockEntity) (Object) this, target);
	}

	/**
	 * An anchor left with one curve may be a free end again; its tick lays that curve flat. Not right away: Create
	 * briefly removes and re-adds curves when tilting a block, and those must not be flattened.
	 */
	@Inject(method = "removeConnection", at = @At("RETURN"))
	private void railwaysUnlimited$flattenFreeEnd(BlockPos target, CallbackInfo ci) {
		TrackBlockEntity self = (TrackBlockEntity) (Object) this;
		Level level = self.getLevel();
		if (level != null && !level.isClientSide && AngledTrackBlock.isAnchor(self.getBlockState()))
			level.scheduleTick(self.getBlockPos(), self.getBlockState().getBlock(), 1);
	}
}
