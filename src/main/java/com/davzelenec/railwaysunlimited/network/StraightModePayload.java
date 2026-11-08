package com.davzelenec.railwaysunlimited.network;

import com.davzelenec.railwaysunlimited.RailwaysUnlimited;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the straight-track key and, together with it, the keys that keep the height or keep the grade.
 */
public record StraightModePayload(boolean held, boolean keepHeight, boolean keepGrade)
	implements CustomPacketPayload {
	public static final Type<StraightModePayload> TYPE = new Type<>(RailwaysUnlimited.asResource("straight_mode"));
	public static final StreamCodec<ByteBuf, StraightModePayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, StraightModePayload::held,
		ByteBufCodecs.BOOL, StraightModePayload::keepHeight,
		ByteBufCodecs.BOOL, StraightModePayload::keepGrade,
		StraightModePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(StraightModePayload payload, IPayloadContext context) {
		context.enqueueWork(() -> SnapSettings.setServerStraight(context.player(), payload.held(),
			payload.keepHeight(), payload.keepGrade()));
	}
}
