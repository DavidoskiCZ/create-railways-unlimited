package com.davzelenec.railwaysunlimited.network;

import com.davzelenec.railwaysunlimited.RailwaysUnlimited;
import com.davzelenec.railwaysunlimited.placement.AngledTrackPlacement;
import com.davzelenec.railwaysunlimited.placement.PlacementPlan;
import com.davzelenec.railwaysunlimited.snap.PlayerSettings;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllTags;

import io.netty.buffer.ByteBuf;
import net.createmod.catnip.codecs.stream.CatnipStreamCodecs;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the player clicked the middle of an angled straight (a curve, which has no block to click) while
 * connecting track. {@code bePos} holds the curve, {@code curveKey} is its other end, {@code aim} the aimed point.
 */
public record ConnectToLinePayload(BlockPos bePos, BlockPos curveKey, Vec3 aim, boolean maximise)
	implements CustomPacketPayload {
	public static final Type<ConnectToLinePayload> TYPE = new Type<>(RailwaysUnlimited.asResource("connect_to_line"));
	public static final StreamCodec<ByteBuf, ConnectToLinePayload> STREAM_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, ConnectToLinePayload::bePos,
		BlockPos.STREAM_CODEC, ConnectToLinePayload::curveKey,
		CatnipStreamCodecs.VEC3, ConnectToLinePayload::aim,
		ByteBufCodecs.BOOL, ConnectToLinePayload::maximise,
		ConnectToLinePayload::new);
	private static final double MAX_DISTANCE = PlayerSettings.MAX_MAX_LENGTH + 64;

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(ConnectToLinePayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			Player player = context.player();
			ItemStack stack = player.getMainHandItem();
			if (!AllTags.AllBlockTags.TRACKS.matches(stack) || !stack.has(AllDataComponents.TRACK_CONNECTING_FROM))
				return;
			if (player.distanceToSqr(Vec3.atCenterOf(payload.bePos())) > MAX_DISTANCE * MAX_DISTANCE)
				return;
			PlacementPlan plan = AngledTrackPlacement.planIntoLine(player.level(), player, payload.bePos(),
				payload.curveKey(), payload.aim(), stack, payload.maximise(), SnapSettings.getStep(player));
			AngledTrackPlacement.finish(player.level(), player, stack, plan, payload.bePos());
		});
	}
}
