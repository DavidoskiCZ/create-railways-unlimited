package com.davzelenec.railwaysunlimited.network;

import com.davzelenec.railwaysunlimited.RailwaysUnlimited;
import com.davzelenec.railwaysunlimited.snap.PlayerSettings;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;
import com.davzelenec.railwaysunlimited.snap.WorldSettings;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The player's settings for the current save. Client to server when changed in the settings screen (the server
 * stores them in the save); server to client when the player joins.
 */
public record PlayerSettingsPayload(PlayerSettings settings) implements CustomPacketPayload {
	public static final Type<PlayerSettingsPayload> TYPE = new Type<>(RailwaysUnlimited.asResource("player_settings"));
	public static final StreamCodec<ByteBuf, PlayerSettingsPayload> STREAM_CODEC =
		PlayerSettings.STREAM_CODEC.map(PlayerSettingsPayload::new, PlayerSettingsPayload::settings);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handleOnServer(PlayerSettingsPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			MinecraftServer server = context.player().getServer();
			if (server != null)
				WorldSettings.get(server).set(context.player().getUUID(), payload.settings().sanitized());
		});
	}

	public static void handleOnClient(PlayerSettingsPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> SnapSettings.setClientSettings(payload.settings()));
	}
}
