package com.davzelenec.railwaysunlimited.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.davzelenec.railwaysunlimited.block.AngledTrackBlock;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Create assumes every track block has some track of its own. An anchor whose last curve was just removed has none,
 * and adding it to the graph would fail; it removes itself a tick later anyway.
 */
@Mixin(TrackPropagator.class)
public abstract class TrackPropagatorMixin {
	@Inject(method = "onRailAdded", at = @At("HEAD"), cancellable = true)
	private static void railwaysUnlimited$skipEmptyAnchor(LevelAccessor reader, BlockPos pos, BlockState state,
														  CallbackInfoReturnable<TrackGraph> cir) {
		if (AngledTrackBlock.isAnchor(state)
			&& (!state.getValue(TrackBlock.HAS_BE) || AngledTrackBlock.connections(reader, pos).isEmpty()))
			cir.setReturnValue(null);
	}
}
